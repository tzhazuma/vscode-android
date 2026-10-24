package com.vscode.android;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Put the Python Environment Tool ({@code pet}) where the Python extensions look for it.
 *
 * <p>{@code ms-python.vscode-python-envs} 1.40.0 is installed as the <b>universal</b> VSIX: it
 * contains only JavaScript and looks for the native helper at
 * {@code <extension>/python-env-tools/bin/pet}. The platform VSIXes are the ones that ship that
 * binary, and Open VSX has no {@code linux-arm64} build of the extension at all -- the API returns
 * "Extension not found" for every target. The Python extension therefore dies with
 * {@code spawn .../python-env-tools/bin/pet ENOENT} as soon as an interpreter is selected.
 *
 * <p>The checked-in binary is a real build of
 * {@code github.com/microsoft/python-environment-tools} (v2024.16.0) for
 * {@code aarch64-linux-android}, built with the NDK; see {@code prebuilt/BUILD.md}. It is copied
 * into every installed copy of either Python extension at boot. If a future VSIX ever ships its
 * own {@code pet}, the existing executable is left alone: this only fills in a missing file.
 */
final class PetPatch {

    private static final String[] EXTENSION_PREFIXES = {
            "ms-python.vscode-python-envs-",
            "ms-python.python-"
    };

    private PetPatch() { }

    static void apply(Context ctx) {
        File extRoot = new File(new File(ctx.getFilesDir(), ".local/share/code-server"),
                "extensions");
        File[] dirs = extRoot.listFiles();
        if (dirs == null) return;
        int installed = 0;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            boolean matches = false;
            for (String prefix : EXTENSION_PREFIXES) {
                if (dir.getName().startsWith(prefix)) {
                    matches = true;
                    break;
                }
            }
            if (!matches) continue;

            File bin = new File(dir, "python-env-tools/bin");
            File pet = new File(bin, "pet");
            // A platform VSIX that already carries its own tool wins; never clobber it.
            if (pet.isFile() && pet.length() > 0 && pet.canExecute()) continue;

            File tmp = new File(bin, "pet.tmp");
            try {
                //noinspection ResultOfMethodCallIgnored
                bin.mkdirs();
                try (InputStream in = ctx.getResources().openRawResource(R.raw.pet);
                     OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.setExecutable(true, false);
                if (!tmp.renameTo(pet)) {
                    //noinspection ResultOfMethodCallIgnored
                    pet.delete();
                    if (!tmp.renameTo(pet)) {
                        throw new java.io.IOException("cannot replace " + pet);
                    }
                }
                //noinspection ResultOfMethodCallIgnored
                pet.setExecutable(true, false);
                installed++;
            } catch (Throwable t) {
                BootLog.say("pet: failed for " + dir.getName() + ": " + t);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        if (installed > 0) {
            BootLog.say("pet: installed into " + installed + " Python extension(s)");
        }
    }
}
