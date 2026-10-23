package com.vscode.android;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Ask the bundled node to open a pty and run something in it.
 *
 * <p>The web workbench reports "Unknown channel: ptyHost" and never creates a terminal, which can
 * mean two very different things: node-pty itself is broken on this device, or code-server never
 * gets as far as starting its pty host. Running node-pty directly answers that question without
 * going through the editor.
 */
public final class PtySelfTest {

    private PtySelfTest() { }

    private static final String SCRIPT = ""
            + "const path = require('path');\n"
            + "const root = process.argv[2];\n"
            + "const cp = require('child_process');\n"
            // code-server starts its pty host by forking bootstrap-fork.js with this entrypoint.
            // Do exactly that and print whatever the child says -- a child that dies before it
            // connects is the one thing the editor's own log cannot show.
            + "const child = cp.fork(path.join(root, 'out/bootstrap-fork.js'), [], {\n"
            + "  cwd: root, silent: true,\n"
            + "  env: Object.assign({}, process.env,\n"
            + "      { VSCODE_ESM_ENTRYPOINT: 'vs/platform/terminal/node/ptyHostMain' })\n"
            + "});\n"
            + "let out = '';\n"
            + "child.stdout.on('data', d => { out += d.toString(); });\n"
            + "child.stderr.on('data', d => { out += d.toString(); });\n"
            + "child.on('error', e => { out += 'SPAWN_ERROR ' + e.message + '\\n'; });\n"
            + "let done = false;\n"
            + "const finish = (tag) => { if (done) return; done = true;\n"
            + "  console.log('PTYHOST_' + tag + ' ' + JSON.stringify(out.slice(0, 700)));\n"
            + "  process.exit(0); };\n"
            + "child.on('exit', c => finish('EXIT code=' + c));\n"
            + "setTimeout(() => finish('ALIVE'), 7000);\n";

    /** Run once at boot; logs one line that either proves node-pty works or names its failure. */
    public static void run(Context ctx) {
        try {
            File js = new File(ctx.getFilesDir(), "pty-test.js");
            try (FileOutputStream w = new FileOutputStream(js)) {
                w.write(SCRIPT.getBytes(StandardCharsets.UTF_8));
            }
            File root = new File(ctx.getFilesDir(), "cs/lib/vscode");
            // The app already runs libnode.so with LD_LIBRARY_PATH pointing at nativeLibraryDir,
            // so reuse exactly that: same binary, same loader path, same environment.
            String node = new File(ctx.getApplicationInfo().nativeLibraryDir, "libnode.so")
                    .getAbsolutePath();
            ProcessBuilder pb = new ProcessBuilder(node, js.getAbsolutePath(),
                    root.getAbsolutePath());
            // Reuse the app's own environment instead of guessing: node needs LD_LIBRARY_PATH
            // for libz.so.1 (it lives in files/rt, not nativeLibraryDir) and OPENSSL_CONF, or it
            // dies with "CANNOT LINK ... library libz.so.1 not found" / "OpenSSL configuration
            // error" before running a line of script.
            VscodeRuntime.applyEnv(pb.environment(), ctx);
            Process p = pb.redirectErrorStream(true).start();
            byte[] b = new byte[2048];
            int n = p.getInputStream().read(b);
            p.waitFor();
            BootLog.say("pty selftest: " + (n > 0 ? new String(b, 0, n).trim() : "(no output)"));
        } catch (Throwable t) {
            BootLog.say("pty selftest threw " + t);
        }
    }
}
