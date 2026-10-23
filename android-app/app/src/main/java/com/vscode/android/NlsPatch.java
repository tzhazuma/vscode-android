package com.vscode.android;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stop the bundled NLS lookup from throwing.
 *
 * <p>The server bundle ships a compiled localisation helper:
 *
 * <pre>
 * function Xl(r, e) {
 *   const t = jl()?.[r];
 *   if (typeof t != "string") {
 *     if (typeof e == "string") return e;
 *     throw new Error(`!!! NLS MISSING: ${r} !!!`);
 *   }
 *   return t;
 * }
 * </pre>
 *
 * <p>and its message table does not line up with the code: the pty host asks for index 3466 and
 * gets nothing back, so the helper throws, the forked pty host dies before it connects, and the
 * editor reports "Unknown channel: ptyHost" with no terminal ever appearing. VSCODE_NLS=false
 * does not help because the throw is in this function, not in the loader.
 *
 * <p>Replacing the throw with an empty string degrades the affected message to blank text, which
 * is a far better outcome than a dead terminal. The rewrite runs once after extraction and
 * touches only files that actually contain the throw.
 */
public final class NlsPatch {

    private static final Pattern THROW = Pattern.compile(
            "throw new Error\\(`[^`]*NLS MISSING[^`]*`\\)");

    private NlsPatch() { }

    /** Patch every affected .js under {@code cs/lib/vscode/out}; returns the file count. */
    public static int apply(Context ctx) {
        File out = new File(ctx.getFilesDir(), "cs/lib/vscode/out");
        File stamp = new File(ctx.getFilesDir(), "cs/.nls-patched");
        if (stamp.isFile()) return 0;
        int patched = 0;
        patched += walk(out, 0);
        try {
            //noinspection ResultOfMethodCallIgnored
            stamp.createNewFile();
        } catch (Exception ignored) {
            // Re-patching next start is harmless: the replacement no longer matches the pattern.
        }
        return patched;
    }

    private static int walk(File dir, int depth) {
        if (depth > 8) return 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        int n = 0;
        for (File f : kids) {
            if (f.isDirectory()) {
                n += walk(f, depth + 1);
            } else if (f.getName().endsWith(".js") && f.length() < 40L * 1024 * 1024) {
                n += patch(f) ? 1 : 0;
            }
        }
        return n;
    }

    private static boolean patch(File f) {
        try {
            byte[] raw = Files.readAllBytes(f.toPath());
            String text = new String(raw, StandardCharsets.UTF_8);
            if (!text.contains("NLS MISSING")) return false;
            Matcher m = THROW.matcher(text);
            if (!m.find()) return false;
            String fixed = m.replaceAll("return \\\"\\\"");
            Files.write(f.toPath(), fixed.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
