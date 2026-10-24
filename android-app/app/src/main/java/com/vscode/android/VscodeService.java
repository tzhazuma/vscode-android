package com.vscode.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import java.io.File;

/**
 * Foreground service that owns the Node process running `dsh web`.
 *
 * WHY A SERVICE: the host is a long-lived server. While it lived in the
 * activity, switching tasks let the system freeze/reclaim it and the Web UI died
 * with it. A foreground service is the only Android construct that promises the
 * process keeps running while the app is not visible.
 *
 * The service is started with startForegroundService() and calls
 * startForeground() immediately, before any slow work, so the platform does not
 * kill us for exceeding the background-start window during the (30 s+) first-run
 * bundle extraction.
 */
public class VscodeService extends Service {
    public static final String TAG = "VscodeService";
    private static final String CHANNEL = "dsh-host";
    private static final int NOTIFICATION_ID = 1;

    private static volatile Process process;
    private static volatile boolean started;

    private Thread worker;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BootLog.init(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification("starting dsh host..."));
        if (!started) {
            started = true;
            worker = new Thread(this::boot, "dsh-boot");
            worker.start();
        }
        // START_STICKY: if the ROM reclaims us anyway, bring the host back.
        return START_STICKY;
    }

    private Notification notification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL, "dsh host", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Keeps the dsh Node host alive");
                nm.createNotificationChannel(ch);
            }
        }
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return b.setContentTitle("dsh")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void note(String text) {
        try {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.notify(NOTIFICATION_ID, notification(text));
        } catch (Throwable ignored) {
        }
    }

    private void boot() {
        File files = getFilesDir();
        File nld = new File(getApplicationInfo().nativeLibraryDir);
        try {
            BootLog.init(this);
            BootLog.say("=== dsh native APK boot (foreground service) ===");
            BootLog.say("targetSdk=" + getApplicationInfo().targetSdkVersion
                    + " nativeLibraryDir=" + nld);

            // ---- 1. runtime shared libraries from assets -> filesDir/rt ----
            File rt = VscodeRuntime.rtDir(this);
            if (!rt.isDirectory()) {
                int n = LogFile.extractAssetDir(this, "rt", rt);
                BootLog.say("extracted runtime assets: " + n);
            }
            chmodTree(rt);

            // ---- 2. prove we can exec the bundled Node runtime ----
            File node = new File(nld, "libnode.so");
            BootLog.say("node=" + node + " exists=" + node.exists() + " canExec=" + node.canExecute());
            Exec.Result a = Exec.run(
                    Exec.cmd(node.getAbsolutePath(), "-e",
                            "console.log('NODE_OK ' + process.version + ' ' + process.platform + '/' + process.arch)"),
                    files,
                    env -> VscodeRuntime.applyEnv(env, this), 30000);
            BootLog.say("node exec: " + a.toString().trim());

            // ---- 3. unpack the dsh bundle ----
            File dsh = VscodeRuntime.csDir(this);
            // v2: the bundle now carries the Android hard-link fallback for
            // dsh-session-persistence-jsonl (SELinux denies link(2) in app data).
            File marker = new File(dsh, ".extracted-vsl-v1");
            if (!marker.isFile()) {
                BootLog.say("extracting code-server bundle (this takes a moment)...");
                long t0 = System.currentTimeMillis();
                int n = ZipExtractor.extract(this, "cs-bundle.zip", dsh);
                BootLog.say("extracted " + n + " files in " + (System.currentTimeMillis() - t0) + " ms");
                // A zip entry carries no Unix mode that Android's extractor preserves, so every
                // helper that needs to be executable comes out mode 600. ripgrep was the visible
                // casualty ("spawn .../rg EACCES"), which breaks search and anything that shells
                // out to it; the rest are node helpers under node_modules/**/bin.
                int fixed = ExecutableBits.restore(dsh);
                BootLog.say("restored executable bits on " + fixed + " helpers");
                //noinspection ResultOfMethodCallIgnored
                marker.createNewFile();
            } else {
                BootLog.say("code-server bundle already extracted");
            }

            // ---- 3b. executables must live in nativeLibraryDir, not here ----
            VscodeRuntime.linkExecutables(this, nld);

            // ---- 3c. the SAF command-line front door used by dsh ----
            SafBridge.installCli(this);

            // ---- 4. Termux is the bash backend, nothing more ----
            BootLog.say("Termux dispatch: " + TermuxDispatch.dispatchEcho(this).trim());

            // ---- 5. start the dsh host ----
            // Before the server starts: it reads product.json once, and a gallery added
            // afterwards would never be seen by the workbench.
            // Both must run before the server starts: it reads product.json and the user
            // settings once, at startup.
            TermuxBootstrap.writeTerminalProfiles(this);
            GalleryPatch.apply(this);
            TerminalProfilesPatch.apply(this);
            // Must be in place before the extension host can resolve an interpreter: the universal
            // vscode-python-envs VSIX has no platform binaries, so pet comes from res/raw/pet.
            PetPatch.apply(this);
            BootLog.say("starting code-server (port is picked inside startWeb) ...");
            process = VscodeRuntime.startWeb(this, nld, new VscodeRuntime.Listener() {
                @Override public void onUrl(String url) {
                    BootLog.say("VSCODE_WEB_URL " + url);
                    BootLog.setUrl(url);
                    note("running on " + url);
                }
                @Override public void onExit(int rc) {
                    BootLog.say("dsh web process exited rc=" + rc);
                    BootLog.setDone(true);
                }
            });
            BootLog.say("dsh web launched, pid=" + (process != null ? "ok" : "?"));
            // Can this app exec a binary from its own data directory? Android 10+ forbids it
            // unless targetSdk < 29, which is the loophole Termux lives on. This decides whether
            // a Termux bootstrap can run here at all, so it is measured rather than assumed.
            try {
                File tb = new File(getFilesDir(), "testbash");
                java.io.InputStream in = getAssets().open("testbash");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(tb)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                in.close();
                tb.setExecutable(true, false);
                Process pr = new ProcessBuilder(tb.getAbsolutePath(), "-c", "echo EXEC_FROM_DATA_OK")
                        .redirectErrorStream(true).start();
                byte[] outb = new byte[256];
                int got = pr.getInputStream().read(outb);
                int rc = pr.waitFor();
                BootLog.say("exec-from-data rc=" + rc + " out="
                        + (got > 0 ? new String(outb, 0, got).trim() : ""));
            } catch (Throwable t) {
                BootLog.say("exec-from-data threw " + t);
            }
            // Real bash from Termux's bootstrap, unpacked into files/usr. Only possible because
            // this app targets API 28 (see TermuxBootstrap and the exec-from-data test above).
            TermuxBootstrap.ensure(this);
            // Shell scripts inside the prefix cannot glob /data/app to find the native library
            // directory (the app's own uid cannot scan that path), so record it once per boot.
            try {
                try (java.io.FileOutputStream w = new java.io.FileOutputStream(
                        new java.io.File(getFilesDir(), "native-lib-dir"))) {
                    w.write(getApplicationInfo().nativeLibraryDir
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException e) {
                BootLog.say("could not record nativeLibraryDir: " + e);
            }
            BootLog.say("nls patch: " + NlsPatch.apply(this) + " files");
            PtySelfTest.run(this);
            ProotSelfTest.run(this);
            // Reach the *separate* Termux app over ssh: install our key there and start sshd.
            try {
                String sshErr = TermuxSsh.authorise(this);
                BootLog.say("termux ssh: " + (sshErr == null ? "authorised" : sshErr));
            } catch (Throwable t) {
                BootLog.say("termux ssh: " + t);
            }
            TermuxBootstrap.ensurePackages(this);
            try {
                File sh = new File(TermuxBootstrap.prefix(this), "bin/bash");
                if (sh.isFile() && sh.canExecute()) {
                    Process pr = new ProcessBuilder(sh.getAbsolutePath(),
                            "-c", "echo TERMUX_OK; uname -m; echo $PREFIX")
                            .redirectErrorStream(true).start();
                    byte[] b = new byte[512];
                    int got = pr.getInputStream().read(b);
                    int rc = pr.waitFor();
                    BootLog.say("termux bash rc=" + rc + " out="
                            + (got > 0 ? new String(b, 0, got).trim().replace('\n', '|') : ""));
                } else {
                    BootLog.say("termux bash missing or not executable: " + sh);
                }
            } catch (Throwable t) {
                BootLog.say("termux bash test threw " + t);
            }
            BootLog.say("=== boot done ===");
        } catch (Throwable t) {
            BootLog.say("BOOT_EXCEPTION " + t);
        }
    }

    private void chmodTree(File dir) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) chmodTree(f);
            else {
                //noinspection ResultOfMethodCallIgnored
                f.setExecutable(true, false);
                //noinspection ResultOfMethodCallIgnored
                f.setReadable(true, false);
            }
        }
    }

    public static Process process() {
        return process;
    }

    @Override
    public void onDestroy() {
        BootLog.say("VscodeService destroyed");
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Keep running: the whole point of the foreground service.
        BootLog.say("task removed; service stays alive");
        super.onTaskRemoved(rootIntent);
    }
}
