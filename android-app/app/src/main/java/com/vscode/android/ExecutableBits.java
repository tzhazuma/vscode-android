package com.vscode.android;

import java.io.File;
import java.util.Locale;

/**
 * Puts the executable bit back on helpers that were packaged in a zip.
 *
 * <p>Android's zip extraction does not restore Unix permissions, so every binary shipped inside
 * {@code cs-bundle.zip} arrives as mode 600. The visible symptom was ripgrep:
 *
 * <pre>Error: spawn .../ripgrep-universal/bin/linux-arm64/rg EACCES</pre>
 *
 * <p>which breaks search and every extension that shells out to it.
 */
final class ExecutableBits {

    private ExecutableBits() { }

    /** Names that are binaries rather than sources, matched case-insensitively. */
    private static boolean looksExecutable(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("rg") || n.equals("node") || n.equals("tar") || n.equals("git")
                || n.endsWith(".sh") || n.endsWith(".node") || n.endsWith(".so")
                || n.equals("proot") || n.equals("loader") || n.equals("pet");
    }

    /** Walks {@code root} and makes every candidate executable. Returns how many were changed. */
    static int restore(File root) {
        return walk(root, 0);
    }

    private static int walk(File dir, int depth) {
        if (dir == null || depth > 10) return 0;
        File[] entries = dir.listFiles();
        if (entries == null) return 0;
        int changed = 0;
        for (File f : entries) {
            if (f.isDirectory()) {
                changed += walk(f, depth + 1);
                continue;
            }
            // Only inside a bin/ directory or node_modules: a data file called *.so that ends up
            // executable is harmless, a helper that cannot be run is not.
            String parent = f.getParentFile() == null ? "" : f.getParentFile().getName();
            boolean inBin = "bin".equals(parent) || f.getPath().contains("node_modules");
            if (!inBin || !looksExecutable(f.getName())) continue;
            if (f.setExecutable(true, false)) {
                changed++;
            }
        }
        return changed;
    }
}
