// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Shared safe intent resolution for the AFTA backend and explicit UI actions. */
final class TargetLauncher {
    private TargetLauncher() {}

    static Intent resolve(Context ctx, String pkg, boolean amazon) {
        if (pkg == null || pkg.isEmpty() || pkg.equals(ctx.getPackageName())) return null;
        PackageManager pm = ctx.getPackageManager();
        if (amazon) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_HOME);
            intent.setComponent(new ComponentName(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return pm.resolveActivity(intent, 0) == null ? null : intent;
        }
        // Amazon home, its fallback, and another Home on Fire controller are never valid targets.
        if (pkg.equals(RedirectEngine.HOME_PACKAGE) || pkg.equals("com.amazon.tahoe")
                || pkg.equals("com.amazon.firehomestarter") || pkg.equals(RedirectEngine.SETTINGS_PACKAGE)
                || pkg.startsWith("io.github.toolicious.homeonfire")) return null;
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) return null;
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    /** null means submitted, not confirmed foreground. Errors remain visible to the caller. */
    static String launch(Context ctx, String pkg, boolean amazon, String reason) {
        DiagnosticLog.event(ctx, "launch_attempt", pkg, "", reason);
        try {
            Intent intent = resolve(ctx, pkg, amazon);
            if (intent == null) throw new IllegalArgumentException("Target is missing, disabled, or not launchable");
            ctx.startActivity(intent);
            DiagnosticLog.event(ctx, "launch_submitted", pkg,
                    intent.getComponent() == null ? "" : intent.getComponent().getClassName(), reason);
            return null;
        } catch (RuntimeException ex) {
            String detail = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            DiagnosticLog.event(ctx, "launch_failed", pkg, "", detail);
            return detail;
        }
    }
}
