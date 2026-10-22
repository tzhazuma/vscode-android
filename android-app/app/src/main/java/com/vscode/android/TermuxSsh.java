package com.vscode.android;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Point this app's ssh at the *separate* Termux installation, not the one bundled inside it.
 *
 * <p>Termux runs an sshd on 127.0.0.1:8022 as its own user, so reaching it is a matter of getting
 * a public key into its authorized_keys. This app cannot write Termux's private storage, but it
 * holds com.termux.permission.RUN_COMMAND, so it asks Termux to do it: the script appends the key,
 * fixes the permissions and starts sshd if it is not already running.
 *
 * <p>The key pair is generated with the ssh-keygen that ships in the bundled prefix, so no host
 * tooling is involved and the private key never leaves the app's files directory.
 */
public final class TermuxSsh {

    public static final int PORT = 8022;

    private TermuxSsh() { }

    /** The key this app authenticates to Termux with. */
    public static File key(Context ctx) {
        return new File(ctx.getFilesDir(), "ssh/termux_ed25519");
    }

    /** Generate the key if it is missing; returns false if ssh-keygen could not be found. */
    public static boolean ensureKey(Context ctx) {
        File k = key(ctx);
        if (k.isFile()) return true;
        File dir = k.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File gen = new File(TermuxBootstrap.prefix(ctx), "bin/ssh-keygen");
        if (!gen.isFile()) {
            BootLog.say("termux ssh: ssh-keygen missing at " + gen);
            return false;
        }
        try {
            Process p = new ProcessBuilder(gen.getAbsolutePath(),
                    "-t", "ed25519", "-N", "", "-C", "vscode-android", "-f", k.getAbsolutePath())
                    .redirectErrorStream(true).start();
            p.waitFor();
            //noinspection ResultOfMethodCallIgnored
            k.setReadable(true, true);
            BootLog.say("termux ssh: generated key at " + k);
            return k.isFile();
        } catch (Exception e) {
            BootLog.say("termux ssh: could not generate key: " + e);
            return false;
        }
    }

    /**
     * Ask Termux to trust our key and run sshd.
     *
     * <p>Returns null on success, or a short message explaining what failed.
     */
    public static String authorise(Context ctx) {
        if (!ensureKey(ctx)) return "no ssh-keygen in the bundled prefix";
        File pub = new File(key(ctx).getAbsolutePath() + ".pub");
        if (!pub.isFile()) return "no public key to install";
        String pubText;
        try {
            pubText = new String(Files.readAllBytes(pub.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "cannot read the public key: " + e;
        }
        // Single-quote the key so the shell does not touch its base64 or comment.
        String quoted = "'" + pubText.replace("'", "'\\''") + "'";
        String script = ""
                + "mkdir -p ~/.ssh && chmod 700 ~/.ssh\n"
                + "touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys\n"
                + "grep -qxF " + quoted + " ~/.ssh/authorized_keys || echo " + quoted
                + " >> ~/.ssh/authorized_keys\n"
                // No pgrep/pidof here: procps is not part of the base Termux install, so the
                // first version of this script died on "pgrep: not found" and sshd never
                // started. Running sshd unconditionally is fine -- if it is already up it just
                // fails to bind and we ignore that.
                + "command -v sshd >/dev/null || { echo NO_SSHD; exit 0; }\n"
                + "[ -f ~/.ssh/ssh_host_ed25519_key ] || ssh-keygen -A >/dev/null 2>&1\n"
                + "sshd 2>/dev/null || true\n"
                + "sleep 1\n"
                + "echo SSHD_STARTED\n";
        String err = TermuxDispatch.dispatch(ctx, script, "vscode-ssh-authorise");
        return err;
    }

    /** The command a terminal profile runs to get a shell inside the standalone Termux. */
    public static String sshCommand(Context ctx) {
        return "ssh -p " + PORT
                + " -i " + key(ctx).getAbsolutePath()
                + " -o StrictHostKeyChecking=no"
                + " -o UserKnownHostsFile=/dev/null"
                + " -o LogLevel=ERROR"
                + " 127.0.0.1";
    }
}
