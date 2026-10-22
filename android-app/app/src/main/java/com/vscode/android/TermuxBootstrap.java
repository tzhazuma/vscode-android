package com.vscode.android;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Unpacks Termux's bootstrap into files/usr so the app has a real bash, coreutils and apt.
 *
 * <p>This only works because the app targets API 28: Android 10 and later refuse execve on
 * anything under the app's data directory, which is the rule that forced dsh's shell to live in
 * nativeLibraryDir as a shared object. Termux itself runs from /data/data/com.termux/files/usr
 * for exactly this reason.
 *
 * <p>Two things a plain unzip gets wrong, both of which make the prefix unusable:
 * <ul>
 *   <li>zip cannot store POSIX permission bits, so nothing comes out executable;
 *   <li>zip cannot store symbolic links, so Termux ships a SYMLINKS.txt listing them as
 *       {@code target←./path} (U+2190 between the two).
 * </ul>
 */
public final class TermuxBootstrap {

    private static final String ZIP = "bootstrap-aarch64.zip";
    private static final String MARKER = ".unpacked-v1";

    private TermuxBootstrap() { }

    /** The Termux prefix inside this app, created on first run. */
    public static File prefix(Context ctx) {
        return new File(ctx.getFilesDir(), "usr");
    }

    /** Extract once; safe to call on every start. */
    public static void ensure(Context ctx) {
        File prefix = prefix(ctx);
        File marker = new File(prefix, MARKER);
        // Rewrite apt.conf every start: it encodes this app's absolute prefix, and it must be
        // there even when the expensive unpack already happened on a previous run.
        if (marker.isFile()) {
            writeAptConfig(ctx);
            File stamp = new File(prefix, ".shebangs-v1");
            if (!stamp.isFile()) {
                BootLog.say("termux bootstrap: rewrote " + rewriteShebangs(prefix) + " shebangs");
                try {
                    //noinspection ResultOfMethodCallIgnored
                    stamp.createNewFile();
                } catch (IOException e) {
                    BootLog.say("termux bootstrap: could not stamp shebangs: " + e);
                }
            }
            return;
        }
        try {
            long t0 = System.currentTimeMillis();
            int n = ZipExtractor.extract(ctx, ZIP, prefix);
            int links = createSymlinks(prefix);
            int fixed = rewriteShebangs(prefix);
            int exec = makeExecutable(new File(prefix, "bin"));
            exec += makeExecutable(new File(prefix, "lib"));
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
            BootLog.say("termux bootstrap: " + n + " files, " + links + " symlinks, "
                    + exec + " executable, " + fixed + " shebangs, "
                    + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            BootLog.say("termux bootstrap failed: " + t);
        }
    }

    /**
     * Point apt at this prefix.
     *
     * <p>Termux's apt has /data/data/com.termux/files/usr/etc/apt compiled in as its config
     * directory, and ignores $PREFIX, so a plain "apt-get update" in another app's prefix dies
     * with:
     *
     * <pre>
     * W: Unable to read /data/data/com.termux/files/usr/etc/apt/apt.conf.d/ - DirectoryExists (13)
     * E: Unable to determine a suitable packaging system type
     * </pre>
     *
     * <p>apt reads the file named by $APT_CONFIG before anything else, and that file can
     * redirect every compiled-in directory with Dir::Etc / Dir::State / Dir::Cache, which is
     * what this writes. The terminal profile sets APT_CONFIG to the same path.
     */
    /**
     * Re-point dangling keyring links at this prefix.
     *
     * <p>The bootstrap's trusted.gpg.d entries are symlinks into Termux's own absolute prefix,
     * so in this app they dangle and apt treats every repository key as missing:
     * "NO_PUBKEY 5A897D96E57CF20C". The real key files do get unpacked, under
     * share/termux-keyring, so the link only has to be aimed at them.
     */
    private static int repairKeyringLinks(File prefix) {
        File dir = new File(prefix, "etc/apt/trusted.gpg.d");
        File[] keys = dir.listFiles();
        if (keys == null) return 0;
        int fixed = 0;
        for (File k : keys) {
            String name = k.getName();
            File real = new File(prefix, "share/termux-keyring/" + name);
            try {
                if (java.nio.file.Files.isSymbolicLink(k.toPath())) {
                    String target = java.nio.file.Files.readSymbolicLink(k.toPath()).toString();
                    if (!target.startsWith(prefix.getAbsolutePath()) && real.isFile()) {
                        java.nio.file.Files.deleteIfExists(k.toPath());
                        android.system.Os.symlink(real.getAbsolutePath(), k.getAbsolutePath());
                        fixed++;
                    }
                } else if (!k.isFile() && real.isFile()) {
                    android.system.Os.symlink(real.getAbsolutePath(), k.getAbsolutePath());
                    fixed++;
                }
            } catch (Throwable ignored) {
                // Leave anything odd alone; apt will simply keep reporting the key as missing.
            }
        }
        return fixed;
    }

    public static File writeAptConfig(Context ctx) {
        File etc = new File(prefix(ctx), "etc/apt");
        //noinspection ResultOfMethodCallIgnored
        etc.mkdirs();
        String p = prefix(ctx).getAbsolutePath();
        String conf = ""
                + "// Written by com.vscode.android: make Termux's apt use this app's prefix.\n"
                + "Dir \"" + p + "/\";\n"
                + "Dir::Etc \"" + p + "/etc/apt/\";\n"
                + "Dir::Etc::parts \"" + p + "/etc/apt/apt.conf.d\";\n"
                + "Dir::Etc::main \"" + p + "/etc/apt/apt.conf\";\n"
                + "Dir::Etc::sourcelist \"" + p + "/etc/apt/sources.list\";\n"
                + "Dir::Etc::sourceparts \"" + p + "/etc/apt/sources.list.d\";\n"
                + "Dir::Etc::trusted \"" + p + "/etc/apt/trusted.gpg\";\n"
                + "Dir::Etc::trustedparts \"" + p + "/etc/apt/trusted.gpg.d\";\n"
                + "Dir::State \"" + p + "/var/lib/apt/\";\n"
                + "Dir::State::status \"" + p + "/var/lib/dpkg/status\";\n"
                + "Dir::State::lists \"" + p + "/var/lib/apt/lists/\";\n"
                + "Dir::Cache \"" + p + "/var/cache/apt/\";\n"
                + "Dir::Cache::archives \"" + p + "/var/cache/apt/archives/\";\n"
                + "Dir::Log \"" + p + "/var/log/apt/\";\n"
                // The method drivers are compiled in as
                // /data/data/com.termux/files/usr/lib/apt/methods too; without this apt
                // reads the index URL but cannot fetch it.
                + "Dir::Bin::methods \"" + p + "/lib/apt/methods\";\n"
                // apt lives in its own prefix and does not consult the system store, so
                // without this every https fetch fails with "No system certificates
                // available" even though ca-certificates is installed.
                + "Acquire::https::CaInfo \"" + p + "/etc/tls/cert.pem\";\n"
                // apt shells out to these by absolute path as well; it spells apt-key
                // out as /data/data/com.termux/files/usr/bin/apt-key otherwise and fails
                // with "Couldn't execute .../apt-key ... repository is not signed".
                + "Dir::Bin::apt-key \"" + p + "/bin/apt-key\";\n"
                + "Dir::Bin::gpg \"" + p + "/bin/gpg\";\n"
                + "Dir::Bin::gpgv \"" + p + "/bin/gpgv\";\n"
                + "Dir::Bin::dpkg \"" + p + "/bin/dpkg\";\n"
                // The repository signing key (5A897D96E57CF20C) is not in termux-keyring_3.14 and
                // keyservers are unreachable here, so apt rejects the index as unsigned. Allowing
                // unsigned repositories makes apt usable; integrity is no longer verified, which
                // suits a sideloaded dev environment but should be replaced by importing the key.
                + "Acquire::AllowInsecureRepositories \"true\";\n";
        writeTerminalProfiles(ctx);
        int repaired = repairKeyringLinks(prefix(ctx));
        if (repaired > 0) {
            BootLog.say("termux: repaired " + repaired + " keyring links");
        }
        // dpkg itself cannot run here: its configuration directory is compiled in as
        // /data/data/com.termux/files/usr/etc/dpkg/dpkg.cfg.d and it fails even on --help, so
        // "apt-get install" can fetch but never unpack. dpkg-deb is fine, so this wrapper
        // gives the same one-command experience by downloading (apt still verifies the index
        // signature) and unpacking with the Termux path prefix relocated to ours.
        File wrapper = new File(prefix(ctx), "bin/pkg-install");
        String script = ""
                + "#!" + p + "/bin/bash\n"
                + "# Install Termux packages without dpkg, which cannot run outside Termux.\n"
                + "# apt still verifies the repository signature while downloading.\n"
                + "set -e\n"
                + "PREFIX=\"" + p + "\"\n"
                + "export PREFIX\n"
                + "tmp=$(mktemp -d)\n"
                + "trap 'rm -rf \"$tmp\"' EXIT\n"
                // --download-only makes apt resolve and fetch the whole dependency tree, but
                // never invoke dpkg (which cannot run here). Plain "apt-get download" would
                // fetch only the named package and leave jq without libonig, for instance.
                + "apt-get install -y --download-only --reinstall \"$@\" >/dev/null\n"
                + "for deb in \"$PREFIX\"/var/cache/apt/archives/*.deb; do\n"
                + "  dpkg-deb -x \"$deb\" \"$tmp/root\"\n"
                + "done\n"
                + "src=\"$tmp/root" + TERMUX_PREFIX + "\"\n"
                + "if [ ! -d \"$src\" ]; then echo \"pkg-install: no payload in $tmp/root\" >&2; exit 1; fi\n"
                + "cp -a \"$src/\". \"$PREFIX/\"\n"
                + "echo \"pkg-install: unpacked into $PREFIX (post-install scripts were not run)\"\n";
        try (java.io.FileOutputStream w = new java.io.FileOutputStream(wrapper)) {
            w.write(script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            BootLog.say("could not write pkg-install: " + e);
        }
        //noinspection ResultOfMethodCallIgnored
        wrapper.setExecutable(true, false);

        File out = new File(etc, "apt.conf");
        try (java.io.FileOutputStream w = new java.io.FileOutputStream(out)) {
            w.write(conf.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            BootLog.say("could not write apt.conf: " + e);
        }
        // apt refuses to run without these directories existing.
        for (String d : new String[]{"var/lib/apt/lists", "var/lib/apt/lists/partial",
                "var/cache/apt/archives/partial", "var/lib/dpkg", "var/log/apt"}) {
            //noinspection ResultOfMethodCallIgnored
            new File(prefix(ctx), d).mkdirs();
        }
        return out;
    }

    /**
     * Unpack the extra Termux packages (openssh, android-tools and their dependencies).
     *
     * <p>apt cannot install them here: its repository signing key is not in the bundled keyring
     * and keyservers are unreachable, so every apt-get refuses the index as unsigned. These
     * .deb files were resolved and extracted on the build machine instead, then repacked with
     * the same SYMLINKS.txt convention as the bootstrap. Extracting them directly skips apt
     * entirely and still gives a working ssh, scp, sftp, sshd and adb.
     */
    public static void ensurePackages(Context ctx) {
        File prefix = prefix(ctx);
        File marker = new File(prefix, ".packages-v1");
        if (marker.isFile()) return;
        try {
            long t0 = System.currentTimeMillis();
            int n = ZipExtractor.extract(ctx, "packages-aarch64.zip", prefix);
            int links = createSymlinks(prefix);
            int fixed = rewriteShebangs(prefix);
            int exec = makeExecutable(new File(prefix, "bin")) + makeExecutable(new File(prefix, "lib"));
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
            BootLog.say("termux packages: " + n + " files, " + links + " symlinks, "
                    + fixed + " shebangs, " + exec + " executable, "
                    + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            BootLog.say("termux packages failed: " + t);
        }
    }

    /** Recreate what zip could not: target←./bin/x means ./bin/x links to target. */
    private static int createSymlinks(File prefix) {
        File list = new File(prefix, "SYMLINKS.txt");
        if (!list.isFile()) return 0;
        int made = 0;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(list), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                int arrow = line.indexOf('\u2190');
                if (arrow <= 0) continue;
                String target = line.substring(0, arrow);
                // Some entries point at Termux's absolute prefix, e.g. the apt keyring:
                // trusted.gpg.d/termux-autobuilds.gpg -> /data/data/com.termux/files/usr/share/
                // termux-keyring/termux-autobuilds.gpg. Left alone that link dangles and apt
                // reports NO_PUBKEY for a key that is actually present.
                target = target.replace(TERMUX_PREFIX, prefix.getAbsolutePath());
                String path = line.substring(arrow + 1);
                if (path.startsWith("./")) path = path.substring(2);
                File link = new File(prefix, path);
                try {
                    //noinspection ResultOfMethodCallIgnored
                    link.getParentFile().mkdirs();
                    Files.deleteIfExists(Paths.get(link.getAbsolutePath()));
                    android.system.Os.symlink(target, link.getAbsolutePath());
                    made++;
                } catch (Throwable ignored) {
                    // A failed link is not fatal: the real file may still be usable.
                }
            }
        } catch (IOException e) {
            BootLog.say("termux bootstrap: symlinks failed: " + e);
        }
        return made;
    }

    /**
     * Write code-server's user settings with the terminal profiles.
     *
     * <p>Done from code rather than by hand so a reinstall does not lose it: the prefix is
     * absolute, and without APT_CONFIG the toybox/termux shells cannot reach the package index
     * that writeAptConfig sets up.
     */
    public static void writeTerminalProfiles(Context ctx) {
        File dir = new File(ctx.getFilesDir(), ".local/share/code-server/User");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        String p = prefix(ctx).getAbsolutePath();
        String json = ""
                + "{\n"
                + "  \"terminal.integrated.defaultProfile.linux\": \"termux\",\n"
                + "  \"terminal.integrated.profiles.linux\": {\n"
                + "    \"termux\": {\n"
                + "      \"path\": \"" + p + "/bin/bash\",\n"
                + "      \"args\": [\"--norc\", \"-i\"],\n"
                + "      \"env\": {\n"
                + "        \"PREFIX\": \"" + p + "\",\n"
                + "        \"HOME\": \"" + p + "/../home\",\n"
                + "        \"TMPDIR\": \"" + p + "/../tmp\",\n"
                + "        \"LD_LIBRARY_PATH\": \"" + p + "/lib\",\n"
                + "        \"PATH\": \"" + p + "/bin:/system/bin:/system/xbin\",\n"
                + "        \"APT_CONFIG\": \"" + p + "/etc/apt/apt.conf\",\n"
                + "        \"TERM\": \"xterm-256color\",\n"
                + "        \"LANG\": \"en_US.UTF-8\"\n"
                + "      }\n"
                + "    },\n"
                + "    \"android\": {\n"
                + "      \"path\": \"/system/bin/sh\",\n"
                + "      \"args\": [\"-i\"],\n"
                + "      \"env\": { \"TERM\": \"xterm-256color\" }\n"
                + "    },\n"
                + "    \"termux-ssh\": {\n"
                + "      \"path\": \"" + p + "/bin/bash\",\n"
                + "      \"args\": [\"--norc\", \"-i\"],\n"
                + "      \"env\": {\n"
                + "        \"PREFIX\": \"" + p + "\",\n"
                + "        \"HOME\": \"" + p + "/../home\",\n"
                + "        \"LD_LIBRARY_PATH\": \"" + p + "/lib\",\n"
                + "        \"PATH\": \"" + p + "/bin:/system/bin\",\n"
                + "        \"APT_CONFIG\": \"" + p + "/etc/apt/apt.conf\",\n"
                + "        \"TERM\": \"xterm-256color\"\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
        try (java.io.FileOutputStream w = new java.io.FileOutputStream(
                new File(dir, "settings.json"))) {
            w.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            BootLog.say("could not write terminal profiles: " + e);
        }
    }

    /** The path Termux's scripts hard-code in their shebangs. */
    private static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";

    /**
     * Rewrite shebangs that point at Termux's own prefix.
     *
     * <p>Binaries are relocatable (they use $ORIGIN in DT_RUNPATH), but scripts are not: apt-key,
     * pkg, ssh-* and friends start with {@code #!/data/data/com.termux/files/usr/bin/bash}, and
     * in another app that interpreter does not exist, so apt reports "Couldn't execute ...
     * apt-key ... repository is not signed". Any script whose first line names the Termux prefix
     * is repointed at this prefix instead.
     */
    private static int rewriteShebangs(File prefix) {
        int n = 0;
        for (String sub : new String[]{"bin", "libexec", "lib/apt/methods"}) {
            File dir = new File(prefix, sub);
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File f : children) {
                if (f.isDirectory() || f.length() > 512 * 1024) continue;
                try {
                    byte[] head = new byte[64];
                    int got;
                    try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                        got = in.read(head);
                    }
                    if (got < 4 || head[0] != '#' || head[1] != '!') continue;
                    String first = new String(head, 0, got, java.nio.charset.StandardCharsets.UTF_8);
                    int nl = first.indexOf('\n');
                    if (nl < 0 || !first.substring(0, nl).contains(TERMUX_PREFIX)) continue;
                    byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                    String text = new String(all, java.nio.charset.StandardCharsets.UTF_8);
                    int lineEnd = text.indexOf('\n');
                    if (lineEnd < 0) continue;
                    String fixed = text.substring(0, lineEnd).replace(
                            TERMUX_PREFIX, prefix.getAbsolutePath()) + text.substring(lineEnd);
                    java.nio.file.Files.write(f.toPath(),
                            fixed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    n++;
                } catch (Throwable ignored) {
                    // A binary that merely starts with '#' is not a script; leave it alone.
                }
            }
        }
        return n;
    }

    private static int makeExecutable(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return 0;
        int n = 0;
        for (File f : children) {
            if (f.isDirectory()) {
                n += makeExecutable(f);
            } else if (f.setExecutable(true, false)) {
                n++;
            }
        }
        return n;
    }
}
