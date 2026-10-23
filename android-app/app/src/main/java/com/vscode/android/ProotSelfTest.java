package com.vscode.android;

import android.content.Context;

/**
 * Ask proot, from the app's own process, to run a trivial command.
 *
 * <p>Every earlier test drove proot through {@code run-as} from adb, which puts the process in a
 * different security context -- so "execve(/system/bin/sh): Permission denied" may have been an
 * artefact of the test harness rather than a real limitation. Running it here, as the app, is the
 * only measurement that matters.
 */
public final class ProotSelfTest {

    private ProotSelfTest() { }

    public static void run(Context ctx) {
        try {
            java.io.File prefix = new java.io.File(ctx.getFilesDir(), "usr");
            java.io.File proot = new java.io.File(prefix, "bin/proot");
            if (!proot.isFile()) {
                BootLog.say("proot selftest: proot not installed");
                return;
            }
            ProcessBuilder pb = new ProcessBuilder(
                    proot.getAbsolutePath(), "-0", "/system/bin/sh", "-c",
                    "echo PROOT_SELFTEST_OK");
            VscodeRuntime.applyEnv(pb.environment(), ctx);
            pb.environment().put("PROOT_TMP_DIR", new java.io.File(ctx.getFilesDir(), "tmp")
                    .getAbsolutePath());
            // applyEnv points LD_LIBRARY_PATH at files/rt for node; proot needs the Termux
            // prefix on the same path or it dies with "library libtalloc.so.2 not found".
            pb.environment().put("LD_LIBRARY_PATH",
                    new java.io.File(prefix, "lib").getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] b = new byte[4096];
            int n = p.getInputStream().read(b);
            p.waitFor();
            BootLog.say("proot selftest: "
                    + (n > 0 ? new String(b, 0, n).trim().replace("\n", " | ") : "(no output)"));
        } catch (Throwable t) {
            BootLog.say("proot selftest threw " + t);
        }
    }
}
