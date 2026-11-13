// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 toolicious; AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious.
package io.github.toolicious.homeonfire;

import android.content.Context;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * Helpers around Android's enabled_accessibility_services secure setting.
 *
 * Background:
 * Normally a user enables an accessibility service via
 * Settings → Accessibility. Some stripped Fire OS builds don't expose that
 * page (clicking it shows a blank screen), which makes activation by hand
 * impossible. As a workaround we can write the secure setting directly,
 * which requires the WRITE_SECURE_SETTINGS permission. That permission is
 * a signature-level permission, so a regular app cannot grant it to itself
 * via runtime prompts. But it can be granted once via adb:
 *
 *     adb shell pm grant io.github.toolicious.homeonfire android.permission.WRITE_SECURE_SETTINGS
 *
 * With the grant in place, our app can self-(re)enable its accessibility
 * service if it ever gets disabled. Without the grant the app simply tells
 * the user how to run that adb command.
 */
public class AccessibilityHelper {

    public static ComponentName component(Context ctx) {
        return new ComponentName(ctx, HijackService.class);
    }

    private static boolean isOurs(Context ctx, String entry) {
        return component(ctx).equals(ComponentName.unflattenFromString(entry));
    }

    /** Connection comes from the service lifecycle, not the secure-setting string. */
    public static boolean isOurServiceConnected() { return HijackService.isConnected(); }

    private static final String PERM_WRITE_SECURE_SETTINGS =
            "android.permission.WRITE_SECURE_SETTINGS";

    /**
     * Returns true if our HijackService is listed in
     * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES. Tolerates both the
     * short ("/.HijackService") and fully qualified component name forms.
     */
    public static boolean isOurServiceEnabled(Context ctx) {
        String value = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(value)) return false;
        for (String entry : value.split(":")) {
            if (isOurs(ctx, entry)) return true;
        }
        return false;
    }

    /**
     * Appends our service to the secure setting (without disturbing any
     * already-enabled services) and switches the master accessibility flag
     * on. Requires WRITE_SECURE_SETTINGS; returns false if the write was
     * rejected (e.g. permission not granted).
     */
    public static boolean enableOurService(Context ctx) {
        if (new Prefs(ctx).usesUsageBackend() || Backend.otherHomeOnFireEnabled(ctx)) return false;
        try {
            String current = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String updated;
            if (TextUtils.isEmpty(current)) {
                updated = component(ctx).flattenToString();
            } else if (isOurServiceEnabled(ctx)) {
                updated = current;
            } else {
                updated = current + ":" + component(ctx).flattenToString();
            }
            boolean listed = Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
            boolean enabled = Settings.Secure.putInt(ctx.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            return listed && enabled;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** True if the WRITE_SECURE_SETTINGS permission has been granted (via adb). */
    public static boolean hasWriteSecureSettings(Context ctx) {
        return ctx.getPackageManager().checkPermission(PERM_WRITE_SECURE_SETTINGS, ctx.getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Removes our service from the secure setting list while leaving
     * any other enabled accessibility services untouched. Requires
     * WRITE_SECURE_SETTINGS; returns false if the write was rejected.
     */
    public static boolean disableOurService(Context ctx) {
        try {
            String current = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (TextUtils.isEmpty(current)) return true;
            StringBuilder kept = new StringBuilder();
            for (String entry : current.split(":")) {
                if (isOurs(ctx, entry)) continue;
                if (kept.length() > 0) kept.append(":");
                kept.append(entry);
            }
            return Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, kept.toString());
        } catch (SecurityException e) {
            return false;
        }
    }
}
