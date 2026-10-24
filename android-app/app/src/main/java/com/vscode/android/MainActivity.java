package com.vscode.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * dsh, as a native Android APK: a WebView over the local dsh web host.
 *
 * The host itself lives in {@link VscodeService} (a foreground service) so it
 * survives task switches. This activity only renders it and hosts the UI
 * surfaces that must run in an Activity: file pickers.
 *
 * The launcher activity is MainActivity; the manifest points here.
 */
public class MainActivity extends Activity {
    private static final String TAG = "VscodeAndroid";
    private TextView status;
    private WebView web;

    /** Ask for the storage the terminal and the file browser need.
     *
     * <p>With targetSdk 28 plus requestLegacyExternalStorage in the manifest, READ/WRITE_EXTERNAL
     * are enough to reach /sdcard; on ROMs that also gate MANAGE_EXTERNAL_STORAGE the settings
     * screen is opened once so the user can grant it there, which is the only path this device
     * allows (appops refuses the shell).
     */

    /** Record whether this app -- in its own context, not via run-as -- can see shared storage.
     *
     * The terminal and the file browser both depend on it, and `run-as` cannot answer the
     * question: it runs in the restricted `runas_app` domain, which is denied external storage
     * regardless of the app's grants. So the app tests it and leaves the answer where it can be
     * read back.
     */
    private void writeStorageCheck() {
        java.io.File f = new java.io.File(getFilesDir(), "storage-check.txt");
        StringBuilder out = new StringBuilder();
        for (String dir : new String[] { "/sdcard", "/storage/emulated/0", "/sdcard/Download" }) {
            String[] names = new java.io.File(dir).list();
            out.append(dir).append(": ")
               .append(names == null ? "DENIED" : ("OK " + names.length + " entries"))
               .append('\n');
        }
        try (java.io.FileOutputStream w = new java.io.FileOutputStream(f)) {
            w.write(out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String p : new String[] {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE }) {
                if (checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    missing.add(p);
                }
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), 4711);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!android.os.Environment.isExternalStorageManager()) {
                    android.content.Intent i = new android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    i.setData(android.net.Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                }
            } catch (Exception e) {
                android.util.Log.w("Storage", "all-files screen unavailable: " + e);
            }
        }
    }


    /**
     * Seed the terminal profiles into the store the workbench actually reads.
     *
     * <p>Written defensively on purpose: the IndexedDB schema belongs to VS Code, not to this app,
     * so the script opens the database, walks every object store, and rewrites only string values
     * that parse as JSON and contain a `_vscode-remote:` settings key. Anything it does not
     * recognise is left untouched, and the whole thing is a no-op when the profiles are already
     * present.
     */
        /**
     * True for URLs that belong to the workbench itself (the bundled code-server on 127.0.0.1).
     *
     * <p>Everything else has to leave the WebView: a plain link click, a {@code target=_blank}
     * link and {@code vscode.env.openExternal} (Kimi Code's device-code login, for one) all end up
     * here. Letting them load in place replaces the workbench with the external page, which is why
     * signing in used to lose the editor.
     */
    private static boolean isWorkbenchUrl(Uri uri) {
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return true;
        String host = uri.getHost();
        return host == null || "127.0.0.1".equals(host) || "localhost".equals(host)
                || "::1".equals(host);
    }

    /** Hand an off-origin URL to the system browser instead of navigating the workbench. */
    private void openInSystemBrowser(Uri uri) {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, uri);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(view);
        } catch (Exception e) {
            android.util.Log.w(TAG, "cannot open externally: " + uri.getScheme()
                    + "://" + uri.getAuthority(), e);
        }
    }

    /**
     * Make the title bar and the terminal's profile chevron usable with a finger.
     *
     * Two overlays were swallowing taps, both measured on the device rather than guessed at:
     *
     * 1. `.titlebar-drag-region` covers the whole title strip and exists so a desktop window can be
     *    dragged by it. On touch, a tap there is taken as the start of a drag, so the command
     *    centre underneath -- the "Search" box -- never receives the click. Disabling its pointer
     *    events lets the click through; a fullscreen Android window has nothing to drag anyway.
     *
     * 2. The terminal panel's profile chevron is 16 px wide and overlaps the `+` beside it by
     *    3 px (measured: `+` at x=759..781, chevron at x=778..794), so a finger aimed at the
     *    chevron lands on `+` and opens another terminal instead of the profile list. Widening it
     *    and lifting it above `+` makes the two targets distinct.
     */
    private static final String TOUCH_FIX_JS =
        "(function(){try{"
        + "var id='dsh-touch-fix';"
        + "var s=document.getElementById(id)||document.createElement('style');"
        + "s.id=id;"
        + "s.textContent='"
        + ".titlebar-drag-region{pointer-events:none !important}"
        + ".monaco-workbench .part.titlebar .titlebar-drag-region{pointer-events:none !important}"
        // The chevron sits inside .monaco-dropdown-with-primary, not .terminal-actions -- the
        // first selector matched nothing, so the button stayed 16 px wide and went on overlapping
        // the `+` beside it.
        + ".monaco-dropdown-with-primary .dropdown-action-container "
        + ".action-label.codicon-chevron-down{"
        + "min-width:24px !important;justify-content:center;position:relative;z-index:5}"
        // A taller command centre so a finger aimed at the search box does not land in the empty
        // margin above it and hit nothing.
        + ".part.titlebar .command-center{min-height:28px !important}"
        // The Agent Status extension puts a pill in the title bar that physically covers the
        // command centre: measured, the pill is 410,4 471x24 and the command centre 358,4 523x28,
        // so the pill sits on top of the right three quarters of the search box and
        // elementFromPoint over the box returns `agent-status-label`, not the box. Every tap there
        // went to the extension, which is why "the search bar does nothing" survived the earlier
        // fixes and why it came and went: it only fails once that extension has loaded.
        + ".agent-status-container,.agent-status-pill,.agent-status-label,"
        + ".agent-status-input-area{pointer-events:none !important}"
        // The real reason the search bar "did nothing": in compact mode the whole command centre is
        // laid out at 523x28 but with visibility:hidden, its carrier .command-center-center set to
        // display:none, and the quick-pick item hidden too. So the white strip is the title bar
        // background, not an input -- there was nothing to type into, and elementFromPoint over it
        // returned the bar, never an input. Shown and given a width, the same point resolves to
        // `input`, and a real tap on it opens the palette (verified on the device).
        + ".part.titlebar .command-center{visibility:visible !important;"
        // Narrow enough to leave the title bar's own dropdown (x 848) reachable: at 320 px of
        // centre width the strip grew to 731 px and covered it, which is a regression this
        // measurement caught.
        + "max-width:360px !important;margin:0 auto !important}"
        + ".part.titlebar .command-center .command-center-center{display:block !important;"
        + "visibility:visible !important;width:200px !important;min-width:140px !important}"
        + ".part.titlebar .command-center .command-center-quick-pick{visibility:visible !important}"
        + "';"
        + "document.head.appendChild(s);"
        // Deliberately no synthetic-click handler here.
        //
        // One was tried: a capture-phase listener that forwarded any title-strip click to
        // .command-center. It made things worse -- 0 of 3 taps opened the palette afterwards,
        // against 5 of 5 before -- because VS Code binds mousedown on that element itself and the
        // handler's preventDefault/stopPropagation swallowed the real event before it arrived.
        // The CSS above is what actually fixed the original "dead" taps.
+ "}catch(e){}})();";


    private final Handler ui = new Handler(Looper.getMainLooper());
    private int shownGeneration = -1;
    private String loadedUrl;
    private HorizontalScrollView barScroll;
    private ScrollView logPanel;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LogFile.init(this);
        BootLog.init(this);
        SafBridge.attach(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button("SAF Open", v -> SafBridge.safPickOpen()));
        bar.addView(button("SAF Save", v -> {
            // scratch file under <filesDir>/export/, as the shared contract requires
            try {
                java.io.File ex = new java.io.File(getFilesDir(), "export");
                //noinspection ResultOfMethodCallIgnored
                ex.mkdirs();
                java.io.File f = new java.io.File(ex, "dsh-saf-save.txt");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                String body = "dsh SAF save " + new java.util.Date() + "\n"
                        + "written by com.vscode.android\n";
                fos.write(body.getBytes("UTF-8"));
                fos.close();
                SafBridge.safPickSave("dsh-saf-save.txt\t" + f.getAbsolutePath());
            } catch (Exception e) {
                BootLog.say("SAF save scratch failed: " + e);
            }
        }));
        bar.addView(button("Show log", v -> {
            shownGeneration = -1; // force a redraw of the whole buffer
            render();
        }));
        hsv.addView(bar);
        barScroll = hsv;
        root.addView(hsv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f);
        status.setPadding(20, 10, 20, 10);
        sv.addView(status);
        logPanel = sv;
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        // vscode.env.openExternal and target=_blank links go through window.open. With multiple
        // windows disabled the WebView either ignores that or loads it in place (replacing the
        // workbench); enabling it routes them to onCreateWindow, which sends the URL to the real
        // browser instead.
        ws.setSupportMultipleWindows(true);
        ws.setJavaScriptCanOpenWindowsAutomatically(true);
        // The workbench keeps its user settings in the WebView's IndexedDB, not in the server's
        // settings.json -- that file is proven ignored (User/, Machine/ and the workspace's
        // .vscode were each written and each had no effect). Injecting into the store it does read
        // is the only route to a terminal profile, so seed the profiles once per boot.
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Applied on every load, not once: the workbench recreates its DOM when the
                // window reloads, and the stylesheet would go with it.
                view.evaluateJavascript(TOUCH_FIX_JS, null);
            }

            /** API 24+. Main-frame navigations only: sub-frames stay inside the workbench. */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (isWorkbenchUrl(uri)) return false;
                openInSystemBrowser(uri);
                return true;
            }

            /** Legacy path, still used when the API-24 overload is not consulted. */
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                Uri uri = Uri.parse(url);
                if (isWorkbenchUrl(uri)) return false;
                openInSystemBrowser(uri);
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                // An <input type=file> inside dsh comes straight to the vivo picker.
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, SafBridge.REQ_WEB);
                return true;
            }

            /**
             * window.open() from the workbench (vscode.env.openExternal, target=_blank) creates
             * a throw-away WebView here. Its first navigation is the real URL; hand that to the
             * system browser and never add the popup to the view tree, so the workbench stays up.
             */
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          Message resultMsg) {
                WebView popup = new WebView(view.getContext());
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        Uri uri = request.getUrl();
                        if (isWorkbenchUrl(uri)) return false;
                        openInSystemBrowser(uri);
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        Uri uri = Uri.parse(url);
                        if (isWorkbenchUrl(uri)) return false;
                        openInSystemBrowser(uri);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Keep the workbench clear of the status-bar edge.
        //
        // The window is edge-to-edge with the system bars hidden, so the WebView's first ~40 px sit
        // inside the region where a downward swipe opens the status bar -- and on this ROM a tap
        // there is frequently taken by the system instead of reaching the page. That is exactly
        // where VS Code puts its title/search bar, which is why tapping it "often does nothing".
        // Padding the top by the inset moves the bar below the gesture area.
        final int basePadTop = web.getPaddingTop();
        web.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                top = Math.max(bars.top, dp(24));
            } else {
                top = Math.max(insets.getSystemWindowInsetTop(), dp(24));
            }
            v.setPadding(v.getPaddingLeft(), basePadTop + top, v.getPaddingRight(),
                    v.getPaddingBottom());
            return insets;
        });

        setContentView(root);
        hideSystemBars();

        // The host is a foreground service: start it once, never stop it from here.
        Intent svc = new Intent(this, VscodeService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }

        ui.postDelayed(this::poll, 300);
        requestStorageAccess();
        writeStorageCheck();
    }

    /** dsh owns the whole display: no title bar, no system bars. */
    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private Button button(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        b.setOnClickListener(l);
        return b;
    }

    private void render() {
        List<String> lines = BootLog.lines();
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        status.setText(sb.toString());
    }

    private void poll() {
        int g = BootLog.generation();
        if (g != shownGeneration) {
            shownGeneration = g;
            render();
            if (loadedUrl == null) {
                String u = BootLog.url();
                if (u != null) {
                    loadedUrl = u;
                    web.loadUrl(u);
                    // The boot log is useful until the host answers, and in the
                    // way afterwards: once dsh is live the WebView gets the full
                    // window, exactly like the desktop app.
                    hideBootChrome();
                }
            }
        }
        ui.postDelayed(this::poll, 500);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == SafBridge.REQ_OPEN || requestCode == SafBridge.REQ_SAVE) {
            SafBridge.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (requestCode == SafBridge.REQ_WEB) {
            if (fileCallback != null) {
                Uri[] uris = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        List<Uri> list = new ArrayList<>();
                        for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                            list.add(data.getClipData().getItemAt(i).getUri());
                        }
                        uris = list.toArray(new Uri[0]);
                    } else if (data.getData() != null) {
                        uris = new Uri[]{data.getData()};
                    }
                }
                fileCallback.onReceiveValue(uris);
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Collapse the diagnostics bar so the dsh UI owns the whole screen. */
    private void hideBootChrome() {
        ui.post(() -> {
            if (barScroll != null) barScroll.setVisibility(View.GONE);
            if (logPanel != null) logPanel.setVisibility(View.GONE);
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SafBridge.setResumed(true);
    }

    @Override
    protected void onPause() {
        SafBridge.setResumed(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // Deliberately do NOT stop VscodeService: the host must outlive the UI.
        super.onDestroy();
        if (web != null) web.destroy();
    }
}
