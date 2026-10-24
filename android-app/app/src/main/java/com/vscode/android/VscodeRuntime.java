package com.vscode.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the bundled code-server on the bundled Node runtime.
 *
 * ARCHITECTURE (user-specified): dsh itself is a NATIVE APK. Termux is only the
 * bash execution backend, reached through {@link TermuxDispatch}. Nothing here
 * needs Termux to boot dsh.
 */
public final class VscodeRuntime {

    /** The port this process bound; the WebView is pointed at it once the server is up. */
    private static int webPort;
    private static final String TAG = "VscodeRuntime";
    /**
     * dsh prints `dsh web: http://127.0.0.1:PORT/?token=...` on stdout.
     *
     * <p>The port is no longer a constant. It was 8899, and on this device 8899 is already
     * held by Termux's own listener: `dsh web` then died with
     * {@code EADDRINUSE: address already in use 127.0.0.1:8899} two seconds after launch,
     * the webserver plugin never activated, and the WebView stayed empty with nothing but
     * "rc=1" in the log. A port that another application happens to own is not something
     * this app can promise, so it searches for a free one instead.
     */
    private static final Pattern URL = Pattern.compile("http://127\\.0\\.0\\.1:\\d+/\\?token=[A-Za-z0-9_-]+");
    public static final int FIRST_PORT = 8900;
    public static final int LAST_PORT = 8920;

    private static volatile int port = FIRST_PORT;

    /** The port the current (or next) dsh web host binds. */
    public static int port() {
        return port;
    }

    /**
     * Bind-test each port in range and return the first that works.
     *
     * <p>Binding and immediately closing leaves a race -- another process could take the
     * port in between -- but Node re-checks anyway, and a lost race only costs one retry
     * on the next launch. Being wrong about a *fixed* port is worse: it fails every time.
     */
    public static int pickFreePort() {
        for (int p = FIRST_PORT; p <= LAST_PORT; p++) {
            try (java.net.ServerSocket s = new java.net.ServerSocket()) {
                s.bind(new java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("127.0.0.1"), p));
                port = p;
                return p;
            }
            catch (Throwable ignored) {
                // taken (or reserved); try the next one
            }
        }
        port = FIRST_PORT;
        return port;
    }

    public interface Listener {
        void onUrl(String url);
        void onExit(int rc);
    }

    public static File csDir(Context ctx) {
        return new File(ctx.getFilesDir(), "cs");
    }

    public static File rtDir(Context ctx) {
        return new File(ctx.getFilesDir(), "rt");
    }

    /**
     * The environment the bundled Node runtime must run under.
     *
     * LD_LIBRARY_PATH / HOME / TMPDIR are the obvious ones. OPENSSL_CONF is not
     * obvious and was found the hard way:
     *
     * The bundled libcrypto.so.3 comes from Termux, whose OpenSSL build baked
     *   OPENSSLDIR = /data/data/com.termux/files/usr/etc/tls
     * into the library. Our app uid can never read that path, and OpenSSL
     * treats an unreadable config file as a FATAL error:
     *   "OpenSSL configuration error: ... BIO_new_file:Permission denied ...
     *    calling fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf, rb)"
     * which aborts Node before it runs a single line of JavaScript.
     * Pointing OPENSSL_CONF at an empty config we own removes the dependency on
     * Termux's private directory entirely. (Node's TLS trust store is its own
     * bundled CA list, so no real OpenSSL configuration is needed.)
     */
    public static void applyEnv(java.util.Map<String, String> env, Context ctx) {
        File files = ctx.getFilesDir();
        // Two directories, not one. rtDir holds libnode's dependencies; the container prefix holds
        // libpython, libclang and the rest. Listing only rtDir is why the Python extension reported
        // the bundled interpreter as "invalid": it forks the interpreter from this server process,
        // which then could not resolve libpython.so.
        java.io.File prefixLib = new java.io.File(new java.io.File(files, "usr"), "lib");
        env.put("LD_LIBRARY_PATH", rtDir(ctx).getAbsolutePath()
                + (prefixLib.isDirectory() ? ":" + prefixLib.getAbsolutePath() : ""));
        env.put("HOME", files.getAbsolutePath());
        env.put("TMPDIR", files.getAbsolutePath());
        // The app's home directory is files/home (that is where the terminal profiles put HOME),
        // but code-server itself must keep HOME=filesDir: $HOME/.local/share/code-server -- the
        // user data, settings and every installed extension -- lives under it, and moving HOME
        // would move all of that too. Kimi Code resolves its data directory as $KIMI_CODE_HOME
        // first and ~/.kimi-code second, so without this it creates an empty filesDir/.kimi-code
        // and reports "model setup required" even though the config the user copied lives in
        // files/home/.kimi-code.
        env.put("KIMI_CODE_HOME",
                new File(new File(files, "home"), ".kimi-code").getAbsolutePath());
        // filesDir/bin holds the `saf` command (the SAF contract, usable from
        // dsh's shell) -- putting it on PATH is what makes `saf open` work from
        // any command dsh runs.
        env.put("PATH", "/system/bin:/system/xbin:" + new File(files, "bin").getAbsolutePath());
        // Android ships /system/bin/sh (toybox) and no bash at all. dsh's tools and the commands
        // an agent writes assume `bash` exists, so without this every shell step fails with
        // "bash: not found" -- which reads to the agent as "this device has no shell" and makes it
        // give up on editing/running anything. Point SHELL and a `bash` shim at something real.
        // SHELL is what the workbench derives its default terminal profile from, and none of the
        // settings files can supply one: User/, Machine/ and the workspace's .vscode were each
        // written and each ignored, because the web workbench keeps its settings in the browser
        // rather than on the server. Pointing SHELL at the container launcher is the one lever
        // that does not depend on where settings are stored -- the terminal then opens inside
        // Ubuntu, and plain Termux remains available by typing `bash`.
        File ubuntuLauncher = new File(files, "usr/bin/ubuntu");
        env.put("SHELL", ubuntuLauncher.isFile()
                ? ubuntuLauncher.getAbsolutePath()
                : ensureShim(ctx, "bash").getAbsolutePath());
        // Same for the other names a script may hard-code.
        ensureShim(ctx, "sh");
        // The SAF CLI needs to find filesDir without guessing: HOME is filesDir,
        // but make it explicit.
        // Termux is installed on many of these devices and is the only place a real bash lives.
        // Its own uid is the only one allowed to execute files there, so these are *pointers* for
        // tooling and for the RUN_COMMAND bridge -- not something this process can exec directly.
        // curl and everything built on libcurl has a CA path compiled in pointing at the Termux
        // prefix (/data/data/com.termux/files/usr/etc/tls/cert.pem), which this uid cannot read:
        // every TLS request then fails with "error adding trust anchors from file". The variable
        // overrides that path, so setting it here fixes curl for the whole process tree.
        java.io.File caFile = new java.io.File(new java.io.File(files, "usr"), "etc/tls/cert.pem");
        if (caFile.isFile()) {
            env.put("CURL_CA_BUNDLE", caFile.getAbsolutePath());
            env.put("SSL_CERT_FILE", caFile.getAbsolutePath());
        }

        env.put("TERMUX_PREFIX", "/data/data/com.termux/files/usr");
        env.put("TERMUX_BASH", "/data/data/com.termux/files/usr/bin/bash");
        env.put("DSH_SAF_DIR", files.getAbsolutePath());
        env.put("OPENSSL_CONF", ensureOpensslConf(ctx).getAbsolutePath());
        // The bundled nls.messages.js and the compiled code disagree: the pty host asks for
        // message 3466, the table has no such index, and the lookup throws
        //   Uncaught Exception: Error: !!! NLS MISSING: 3466 !!!
        // which kills the pty host before it ever connects -- "Unknown channel: ptyHost" in the
        // editor and no terminal at all. Turning NLS off makes the localisation helper return
        // the key it was given instead of throwing.
        env.put("VSCODE_NLS", "false");
    }

    /**
     * Install (once) a tiny shell shim in filesDir/bin under `name`, pointing at `target`.
     *
     * Android has no bash — only toybox at /system/bin/sh — but dsh's tool descriptions and most
     * of the commands an agent writes start with `bash`. A missing bash makes every shell step
     * fail, which the agent reports as "this device has no shell", and then it refuses to edit or
     * run anything. filesDir/bin is already on PATH, so a one-line exec shim is enough: the
     * script is a real file the app owns, and `/system/bin/sh` is always executable.
     */
    public static File ensureShim (Context ctx, String name)
    {
        File dir = new File(ctx.getFilesDir(), "bin");
        dir.mkdirs();
        File link = new File(dir, name);
        // A two-line script here cannot be exec'd: Android 10 and later refuse execve on
        // anything under the app's data directory, which is exactly what the pty host hit:
        //   ptyHost was unable to resolve shell environment
        //   spawn .../files/bin/bash EACCES
        // and a terminal that never appears. A real ELF in nativeLibraryDir is executable, so
        // the shim lives there and is symlinked into PATH -- the same trick dsh uses.
        File real = new File(ctx.getApplicationInfo().nativeLibraryDir, "libbashsh.so");
        try {
            java.nio.file.Files.deleteIfExists(link.toPath());
            android.system.Os.symlink(real.getAbsolutePath(), link.getAbsolutePath());
        } catch (Throwable t) {
            LogFile.log(TAG, "could not link " + name + " -> " + real + ": " + t);
        }
        return link;
    }

    /** Create (once) an empty-but-valid OpenSSL config inside our own data dir. */
    public static File ensureOpensslConf(Context ctx) {
        File conf = new File(ctx.getFilesDir(), "openssl.cnf");
        if (!conf.isFile()) {
            try (Writer w = new OutputStreamWriter(new FileOutputStream(conf), "UTF-8")) {
                w.write("# dsh-android: intentionally empty.\n"
                        + "# Exists only so the Termux-built libcrypto does not try to\n"
                        + "# read its compiled-in OPENSSLDIR inside Termux's private data.\n");
            } catch (Exception e) {
                LogFile.log(TAG, "could not write openssl.cnf: " + e);
            }
        }
        return conf;
    }

    /**
     * Executables shipped inside the APK bundle must be re-pointed at
     * nativeLibraryDir.
     *
     * Android 10+ forbids execve of files under the app's DATA directory (SELinux
     * untrusted_app has no execute on app_data_file). The check is on the resolved
     * inode, so a *symlink* in the data dir pointing at a real file in
     * nativeLibraryDir (labelled apk_data_file, which IS executable) works fine.
     *
     * This bit us twice, and the second time silently:
     *   - ripgrep: spawnSync -> EACCES.
     *   - landlock-run: probe() reported "unusable", which looks exactly like the
     *     kernel not supporting Landlock. It was really exec being denied.
     *
     * So every bundled executable ships twice: as a real file in the zip (so
     * require.resolve and packaging see it) and as lib&lt;name&gt;.so in jniLibs,
     * then symlinked here.
     */
    public static void linkExecutables(Context ctx, File nativeLibraryDir) {
        String[][] pairs = {
                {"lib/vscode/node_modules/@parcel/watcher/build/Release/watcher.node", "libwatcher.so"},
                {"lib/vscode/node_modules/node-pty/build/Release/pty.node", "libpty.so"},
                {"lib/vscode/node_modules/@vscode/sqlite3/build/Release/vscode-sqlite3.node", "libvscodesqlite3.so"},
                {"lib/vscode/node_modules/@vscode/spdlog/build/Release/spdlog.node", "libspdlog.so"},
                {"node_modules/argon2/build/Release/argon2.node", "libargon2.so"},
        };
        for (String[] pair : pairs) {
            File link = new File(csDir(ctx), pair[0]);
            File target = new File(nativeLibraryDir, pair[1]);
            try {
                if (!target.isFile()) {
                    LogFile.log(TAG, "linkExecutables: missing " + target);
                    continue;
                }
                // unlink unconditionally: exists() follows the link, so a stale
                // link would otherwise fail symlink() with EEXIST and be logged
                // as an error on every boot.
                try {
                    java.nio.file.Files.deleteIfExists(
                            java.nio.file.Paths.get(link.getAbsolutePath()));
                } catch (Throwable ignored) {
                }
                /* Copy, do NOT symlink. Node resolves symlinks by default, so `pty.node` becomes
                 * `libpty.so`, and Module._extensions then has no loader for ".so" -- it falls
                 * back to the JavaScript loader, tries to parse an ELF header as source and dies
                 * with "Invalid or unexpected token". Copying keeps the .node extension on a real
                 * file. Total cost is about 3 MB, which is nothing next to the 47 MB node. */
                java.nio.file.Files.copy(target.toPath(), link.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                link.setReadable(true, false);
                LogFile.log(TAG, "copied " + pair[0] + " <- " + target);
            } catch (Throwable t) {
                LogFile.log(TAG, "linkExecutables failed for " + pair[0] + ": " + t);
            }
        }
    }

    /**
     * Start `dsh web`. Returns the Process so the caller can kill it.
     *
     * --expose-internals is required because node-addon-require-builtin has NO
     * Android implementation upstream (see dsh-android/STATUS.md §5.1); the
     * bundled shim substitutes ordinary require(), which only reaches
     * `internal/...` with that flag.
     */
    public static Process startWeb(Context ctx, File nativeLibraryDir, Listener listener) throws Exception {
        File files = ctx.getFilesDir();
        File node = new File(nativeLibraryDir, "libnode.so");
        File rt = rtDir(ctx);
        File dsh = csDir(ctx);
        File bin = new File(dsh, "out/node/entry.js");
        if (!bin.isFile()) throw new IllegalStateException("dsh entry missing: " + bin);

        // Node reports process.platform === "android", which the server refuses:
        //   Error: Platform not supported   (ptyHostMain.js)
        // Android's userland is close enough to Linux that reporting "linux" is correct for
        // everything vscode does with it. execArgv is inherited by forked children, so the pty
        // host and extension host get the same correction.
        File platfix = new File(files, "platfix.js");
        try (java.io.FileOutputStream w = new java.io.FileOutputStream(platfix)) {
            w.write(("try { Object.defineProperty(process, 'platform', { value: 'linux' }); }"
                    + " catch (e) {}\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        List<String> cmd = Exec.cmd(
                node.getAbsolutePath(),
                "--expose-internals",
                "--require", platfix.getAbsolutePath(),
                bin.getAbsolutePath(),
                "--bind-addr", "127.0.0.1:" + (webPort = pickFreePort()), "--auth", "none",
                // code-server's web client asks before opening an external site (the
                // "Do you want code-server to open the external website?" dialog). Kimi Code's
                // login is a device-code flow: it calls vscode.env.openExternal(verification URL)
                // and only starts polling auth.kimi.com after that call resolves. With the dialog
                // unanswered the call never resolves, the browser page can report success, and the
                // extension still sits signed out. Trusting the two Kimi origins makes openExternal
                // return immediately, so polling begins.
                "--link-protection-trusted-domains", "https://www.kimi.com",
                "--link-protection-trusted-domains", "https://auth.kimi.com");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        final int boundPort = webPort;
        new Thread(() -> {
            try { Thread.sleep(6000); } catch (InterruptedException e) { return; }
            if (listener != null) listener.onUrl("http://127.0.0.1:" + boundPort + "/");
        }, "cs-url").start();
        pb.directory(dsh);
        applyEnv(pb.environment(), ctx);
        LogFile.log(TAG, "exec: " + String.join(" ", cmd));

        final Process p = pb.start();
        final Writer out = new OutputStreamWriter(new FileOutputStream(
                new File(files, "dsh-web.stdout.log")));

        // stdout: scan for the URL; stderr: log verbatim.
        Thread tOut = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    LogFile.log(TAG, "dsh| " + line);
                    out.write(line + "\n");
                    out.flush();
                    Matcher m = URL.matcher(line);
                    if (m.find() && listener != null) listener.onUrl(m.group());
                }
            } catch (Exception ignored) {
            }
        }, "dsh-stdout");
        Thread tErr = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) LogFile.log(TAG, "dsh! " + line);
            } catch (Exception ignored) {
            }
        }, "dsh-stderr");
        tOut.setDaemon(true);
        tErr.setDaemon(true);
        tOut.start();
        tErr.start();

        Thread waiter = new Thread(() -> {
            try {
                int rc = p.waitFor();
                LogFile.log(TAG, "dsh web exited rc=" + rc);
                out.close();
                if (listener != null) listener.onExit(rc);
            } catch (Exception ignored) {
            }
        }, "dsh-wait");
        waiter.setDaemon(true);
        waiter.start();
        return p;
    }

    /** Blocking helper used by the self-test path. */
    public static String runSelfTest(Context ctx, File nativeLibraryDir) throws Exception {
        File rt = rtDir(ctx);
        File node = new File(nativeLibraryDir, "libnode.so");
        final CountDownLatch latch = new CountDownLatch(1);
        final StringBuilder sb = new StringBuilder();
        startWeb(ctx, nativeLibraryDir, new Listener() {
            @Override public void onUrl(String url) { sb.append(url); latch.countDown(); }
            @Override public void onExit(int rc) { latch.countDown(); }
        });
        latch.await();
        return sb.toString();
    }

    private VscodeRuntime() {}
}
