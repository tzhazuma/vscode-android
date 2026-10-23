package com.vscode.android;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Point the Extensions view at Open VSX.
 *
 * <p>code-server ships {@code lib/vscode/product.json} with {@code extensionsGallery: null}, and
 * with no gallery the workbench renders an Extensions panel that lists only what is already on
 * disk -- no search results, nothing to browse. Setting the gallery to Open VSX is what makes the
 * marketplace actually show extensions.
 *
 * <p>This runs on every start rather than being baked into the bundle: the bundle is only
 * extracted when {@code files/cs} is missing, so a patched zip would silently never be applied on
 * an upgrade.
 */
public final class GalleryPatch {

    private static final String GALLERY = ""
            + "\"extensionsGallery\": {\n"
            + "\t\t\"serviceUrl\": \"https://open-vsx.org/vscode/gallery\",\n"
            + "\t\t\"itemUrl\": \"https://open-vsx.org/vscode/item\",\n"
            + "\t\t\"resourceUrlTemplate\": \"https://open-vsx.org/vscode/unpkg/"
            + "{publisher}/{name}/{version}/{path}\",\n"
            + "\t\t\"controlUrl\": \"\",\n"
            + "\t\t\"recommendationsUrl\": \"\"\n"
            + "\t}";

    private GalleryPatch() { }

    public static void apply(Context ctx) {
        File product = new File(ctx.getFilesDir(), "cs/lib/vscode/product.json");
        if (!product.isFile()) return;
        try {
            String text = new String(Files.readAllBytes(product.toPath()), StandardCharsets.UTF_8);
            if (text.contains("open-vsx.org/vscode/gallery")) return;
            String patched;
            if (text.matches("(?s).*\"extensionsGallery\"\\s*:\\s*null.*")) {
                patched = text.replaceFirst("\"extensionsGallery\"\\s*:\\s*null", GALLERY);
            } else {
                return;
            }
            Files.write(product.toPath(), patched.getBytes(StandardCharsets.UTF_8));
            BootLog.say("gallery: extensionsGallery set to Open VSX");
        } catch (Throwable t) {
            BootLog.say("gallery patch failed: " + t);
        }
    }
}
