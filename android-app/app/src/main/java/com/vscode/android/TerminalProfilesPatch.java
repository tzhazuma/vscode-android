package com.vscode.android;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Put a working terminal profile in the picker.
 *
 * <p>Nothing written from outside reaches this workbench: `User/settings.json`,
 * `Machine/settings.json` and the workspace's `.vscode/settings.json` were each written, each
 * verified intact after a restart, and each ignored, and the WebView's IndexedDB holds no settings
 * record at all to patch. Pointing `$SHELL` at the launcher does not help either: VS Code only
 * accepts a known shell name and falls back to `bash`.
 *
 * <p>So the profile list is given a built-in entry, the same way the extensions gallery was given
 * its URL. Two literal edits in the workbench bundle:
 *
 * <ol>
 *   <li>the call that builds the profile list from the setting object;</li>
 *   <li>the loop that reads the same object to honour disabled profiles, which throws when the
 *       setting is absent.</li>
 * </ol>
 *
 * <p>Both edits only ever add. A profile the user configured still wins, because the defaults are
 * merged underneath rather than replacing anything.
 */
final class TerminalProfilesPatch {

    private static final String TAG = "TerminalProfiles";

    /** The workbench bundle, relative to the code-server root. */
    private static final String BUNDLE = "lib/vscode/out/vs/code/browser/workbench/workbench.js";

    private static final String DEFAULT_PROFILES =
            "{\"termux\":{\"path\":\"/data/user/0/com.vscode.android/files/usr/bin/bash\","
                    + "\"args\":[\"--norc\",\"-i\"]},"
                    + "\"ubuntu\":{\"path\":\"/data/user/0/com.vscode.android/files/usr/bin/ubuntu\"}}";

    private TerminalProfilesPatch() { }

    static void apply(Context ctx) {
        File bundle = new File(new File(ctx.getFilesDir(), "cs"), BUNDLE);
        if (!bundle.isFile()) {
            BootLog.say("terminal profiles: bundle missing at " + bundle);
            return;
        }
        try {
            String js = new String(Files.readAllBytes(bundle.toPath()), StandardCharsets.UTF_8);
            if (js.contains("\"ubuntu\":{\"path\"")) {
                BootLog.say("terminal profiles: already present");
                return;
            }

            // 1. the profile list itself
            String from = "t.getProfiles(this._configurationService.getValue(`terminal.integrated.profiles.${i}`),this._defaultProfileName,e)";
            String to = "t.getProfiles(Object.assign(" + DEFAULT_PROFILES + ",this._configurationService.getValue(`terminal.integrated.profiles.${i}`)||{}),this._defaultProfileName,e)";
            int hits = 0;
            if (js.contains(from)) {
                js = js.replace(from, to);
                hits++;
            }

            // 2. the disabled-profile scan, which throws on an absent setting
            String from2 = "i=this._configurationService.getValue(\"terminal.integrated.profiles.\"+e);for(let[a,c]of Object.entries(i))";
            String to2 = "i=this._configurationService.getValue(\"terminal.integrated.profiles.\"+e)||{};for(let[a,c]of Object.entries(i))";
            if (js.contains(from2)) {
                js = js.replace(from2, to2);
                hits++;
            }

            if (hits == 0) {
                BootLog.say("terminal profiles: no injection point found -- bundle changed?");
                return;
            }
            Files.write(bundle.toPath(), js.getBytes(StandardCharsets.UTF_8));
            BootLog.say("terminal profiles: patched " + hits + "/2 injection points");
        } catch (Throwable t) {
            BootLog.say("terminal profiles: failed: " + t);
        }
    }
}
