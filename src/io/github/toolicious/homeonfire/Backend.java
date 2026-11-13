// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

final class Backend {
    static final String USAGE = "usage";
    static final String ACCESSIBILITY = "accessibility";
    private Backend() {}

    static boolean isAfta() {
        return Build.VERSION.SDK_INT == 25 && "AFTA".equals(Build.MODEL)
                && "Amazon".equalsIgnoreCase(Build.MANUFACTURER);
    }

    static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            return ops != null && ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    static boolean hasOverlayPermission(Context context) {
        try {
            return isAfta() && Settings.canDrawOverlays(context);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Never toggle other services. A second Home on Fire instance must be stopped by its owner. */
    static boolean otherHomeOnFireEnabled(Context context) {
        try {
            AccessibilityManager manager = (AccessibilityManager)
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null) return false;
            for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) continue;
                String pkg = info.getResolveInfo().serviceInfo.packageName;
                if (!context.getPackageName().equals(pkg)
                        && pkg.startsWith("io.github.toolicious.homeonfire")) return true;
            }
        } catch (RuntimeException ex) {
            // Failure to check for a competing instance must not enable automation.
            return true;
        }
        return false;
    }
}
