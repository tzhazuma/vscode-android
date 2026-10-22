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
        if (marker.isFile()) return;
        try {
            long t0 = System.currentTimeMillis();
            int n = ZipExtractor.extract(ctx, ZIP, prefix);
            int links = createSymlinks(prefix);
            int exec = makeExecutable(new File(prefix, "bin"));
            exec += makeExecutable(new File(prefix, "lib"));
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
            BootLog.say("termux bootstrap: " + n + " files, " + links + " symlinks, "
                    + exec + " executable, " + (System.currentTimeMillis() - t0) + " ms");
        } catch (Throwable t) {
            BootLog.say("termux bootstrap failed: " + t);
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
