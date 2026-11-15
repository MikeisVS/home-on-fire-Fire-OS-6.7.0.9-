// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; updated on 2026-11-14; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import java.util.ArrayDeque;

/**
 * AFTA launch policy, independent of Android so timing and cancellation are testable.
 * All time arguments are monotonic milliseconds. Only the service submits launches.
 */
final class RedirectEngine {
    static final String HOME_PACKAGE = "com.amazon.tv.launcher";
    static final String HOME_CLASS = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    static final String SETTINGS_PACKAGE = "com.amazon.tv.settings.v2";
    static final String HUD_CLASS = "com.amazon.tv.settings.v2.hud.HudActivity";
    static final String SETTINGS_ENTRY_CLASS =
            "com.amazon.tv.settings.v2.compliance.CTSDummySettingsHandler";
    static final long HOME_DELAY_MS = 5500;
    static final long CONFIRM_TIMEOUT_MS = 10000;
    static final long CIRCUIT_WINDOW_MS = 30000;

    private enum AmazonVisit { NONE, ESCAPE, SETTINGS }

    interface Log {
        void event(String event, String pkg, String cls, String detail);
    }

    /** Presentation signals only; the listener never submits or cancels Android starts. */
    interface CoverListener {
        void show(long requestId, String target);
        void targetObserved(long requestId);
        void cancel(long requestId, String reason);
    }

    static final class Request {
        final String packageName;
        final String reason;
        final boolean amazon;
        final long due;
        final long id;
        Request(String pkg, String reason, boolean amazon, long due) {
            this(pkg, reason, amazon, due, 0);
        }
        Request(String pkg, String reason, boolean amazon, long due, long id) {
            this.packageName = pkg;
            this.reason = reason;
            this.amazon = amazon;
            this.due = due;
            this.id = id;
        }
    }

    private final Log log;
    private final CoverListener cover;
    private final ArrayDeque<Long> attempts = new ArrayDeque<Long>();
    private String target = "";
    private String foregroundPackage = "", foregroundClass = "";
    private String lastAppPackage = "", lastAppClass = "";
    private boolean homeEnabled, bootEnabled, wakeEnabled, diagnosticOnly = true;
    private boolean escapeLongPressEnabled = true;
    private boolean interactive, observationsAllowed, bootPending, bootReceived, tripped;
    private AmazonVisit amazonVisit = AmazonVisit.NONE;
    private long settingsEnteredAt = Long.MIN_VALUE;
    private long lastHomePressedAt = Long.MIN_VALUE;
    private long retryHomeAfter = Long.MIN_VALUE;
    private Request pending, submitted;
    private Request coveredHome;
    private boolean coverObserved;
    private long requestSequence;
    private long submittedAt;
    private String lastResult = "No launch requested";

    RedirectEngine(Log log) { this(log, null); }
    RedirectEngine(Log log, CoverListener cover) { this.log = log; this.cover = cover; }

    void configure(String target, boolean home, boolean boot, boolean wake,
                   boolean diagnostic, boolean suspended) {
        configure(target, home, boot, wake, diagnostic, suspended, true);
    }

    void configure(String target, boolean home, boolean boot, boolean wake,
                   boolean diagnostic, boolean suspended, boolean escapeLongPress) {
        cancel("configuration changed");
        this.target = target == null ? "" : target;
        homeEnabled = home;
        bootEnabled = boot;
        wakeEnabled = wake;
        diagnosticOnly = diagnostic;
        escapeLongPressEnabled = escapeLongPress;
        tripped = suspended;
        bootPending = false;
        // A configuration change must not replay the current launcher visit.
        clearForeground();
    }

    void resume() {
        attempts.clear();
        tripped = false;
        amazonVisit = AmazonVisit.NONE;
        clearHomeSignals();
        cancel("explicit resume");
        log.event("automation_resumed", "", "", "Historical events will not trigger a launch");
    }

    void permission(boolean allowed) {
        if (observationsAllowed == allowed) return;
        observationsAllowed = allowed;
        clearForeground();
        if (!allowed && pending != null && isObservationRequest(pending)) {
            cancel("Usage Access unavailable");
        }
        if (!allowed) cancelCover("Usage Access unavailable");
    }

    void screen(boolean on, boolean wake, long now) {
        boolean changed = interactive != on;
        interactive = on;
        if (!on) {
            if (pending != null && "boot".equals(pending.reason)) bootPending = true;
            cancel("screen off");
            clearForeground();
            return;
        }
        if (!changed) return; // SCREEN_ON / USER_PRESENT / dream-stop may overlap.
        amazonVisit = AmazonVisit.NONE; // A new wake follows the always-open preference.
        if (bootPending) {
            bootPending = false;
            if (bootEnabled) scheduleTarget("boot", now);
        } else if (wake && wakeEnabled) {
            scheduleTarget("wake", now + HOME_DELAY_MS);
        }
    }

    void boot(long now) {
        if (bootReceived) return;
        bootReceived = true;
        bootPending = bootEnabled && !interactive;
        // BOOT_COMPLETED is not a Home key. Read/seed the current state, then let
        // the first eligible poll launch without adding the Home app-switch wait.
        if (interactive && bootEnabled) scheduleTarget("boot", now);
    }

    /** A verified system Home launch supplies a cue, not a raw key. */
    void homePressed(String reason, long receivedAt) {
        if (!"homekey".equals(reason)) return;
        log.event("home_signal", foregroundPackage, foregroundClass,
                "reason=homekey; receivedAt=" + receivedAt);
        if (!homeEnabled || diagnosticOnly || tripped || target.isEmpty()
                || !interactive || !observationsAllowed) return;
        expireSubmitted(receivedAt);
        lastHomePressedAt = Math.max(lastHomePressedAt, receivedAt);
        if (amazonVisit == AmazonVisit.SETTINGS && receivedAt >= settingsEnteredAt) {
            returnFromSettings(receivedAt);
        } else if (retryHomeAfter != Long.MIN_VALUE && receivedAt >= retryHomeAfter
                && pending == null && submitted == null
                && isHome(foregroundPackage, foregroundClass) && !isBypassing()) {
            // A new physical Home can reuse Amazon's existing activity and emit no
            // foreground event. Recover only after the previous Home timed out;
            // never retry an Android start merely because confirmation is absent.
            retryHomeAfter = Long.MIN_VALUE;
            scheduleTarget("home_recovery", receivedAt + HOME_DELAY_MS);
        }
    }

    private void returnFromSettings(long pressedAt) {
        amazonVisit = AmazonVisit.NONE;
        log.event("settings_visit_ended", foregroundPackage, foregroundClass,
                "Home signal; receivedAt=" + pressedAt);
        // The root may already be HomeActivity_vNext and emit no UsageEvent.
        // A subpage instead needs its Home arrival before poll can submit.
        scheduleTarget("settings_home", pressedAt + HOME_DELAY_MS);
    }

    private void clearHomeSignals() {
        settingsEnteredAt = lastHomePressedAt = Long.MIN_VALUE;
    }

    void clearForeground() {
        foregroundPackage = foregroundClass = lastAppPackage = lastAppClass = "";
        retryHomeAfter = Long.MIN_VALUE;
        clearHomeSignals();
    }

    void resetObservation(String why) {
        clearForeground();
        if (pending != null && isObservationRequest(pending)) cancel(why);
        cancelCover(why);
        log.event("observation_reset", "", "", why);
    }

    /** Seed state without inferring a Home or HUD gesture from history. */
    void observe(String pkg, String cls, boolean foreground, boolean fresh, long now) {
        observe(pkg, cls, foreground, fresh, now, now);
    }

    void observe(String pkg, String cls, boolean foreground, boolean fresh, long now, long occurredAt) {
        pkg = pkg == null ? "" : pkg;
        cls = cls == null ? "" : cls;
        if (!foreground) {
            if (pkg.equals(foregroundPackage) && cls.equals(foregroundClass)) {
                foregroundPackage = foregroundClass = "";
                if (pending != null && isObservationRequest(pending)
                        && !"settings_home".equals(pending.reason)) {
                    cancel("activity left foreground");
                }
                // A Home cue from Settings precedes its background/Home handover.
                // Keep that one request until a successor or the guarded poll;
                // an unrelated successor still cancels it below.
                // Amazon going background after submission is the expected handover.
                // A target leaving during the opaque hold is navigation away instead.
                if (coverObserved) cancelCover("target left foreground");
            }
            return;
        }
        boolean same = pkg.equals(foregroundPackage) && cls.equals(foregroundClass);
        String previous = lastAppPackage;
        String previousClass = lastAppClass;
        foregroundPackage = pkg;
        foregroundClass = cls;
        if (!isHome(pkg, cls)) retryHomeAfter = Long.MIN_VALUE;
        boolean hud = isHud(pkg, cls);
        if (!hud) {
            lastAppPackage = pkg;
            lastAppClass = cls;
        }
        if (fresh && submitted != null && occurredAt >= submittedAt
                && submitted.packageName.equals(pkg)
                && (!submitted.amazon || isHome(pkg, cls))) {
            lastResult = "Observed " + pkg + " after " + (now - submittedAt) + " ms";
            log.event("launch_observed", pkg, cls, submitted.reason + "; " + lastResult);
            if (coveredHome != null && coveredHome.id == submitted.id) {
                coverObserved = true;
                cover.targetObserved(coveredHome.id);
            }
            submitted = null;
        }
        if (fresh && coveredHome != null && !isHome(pkg, cls)
                && !coveredHome.packageName.equals(pkg)
                && !isPreHomeSettings(coveredHome, pkg, cls, occurredAt)) {
            cancelCover("another activity appeared");
        }
        if (pending != null && isObservationRequest(pending)) {
            boolean stillExpected = isHomeRequest(pending)
                    ? isHome(pkg, cls) : hud;
            if (isPreHomeSettings(pending, pkg, cls, occurredAt)) {
                stillExpected = true; // Settings published late, before the Home cue.
            }
            if (!stillExpected) cancel("another activity appeared");
        }
        if (!fresh || same || !observationsAllowed || !interactive) return;
        if (!isAmazonVisit(pkg)) {
            amazonVisit = AmazonVisit.NONE;
            if (occurredAt >= lastHomePressedAt) clearHomeSignals();
        }
        // ACTION_SETTINGS briefly enters this handler, then displays the settings
        // root in HomeActivity_vNext. Preserve that visit before the Home event can
        // schedule a redirect or its loading cover. Ordinary settings and the HUD
        // are separate activities and must retain their existing behavior.
        if (SETTINGS_PACKAGE.equals(pkg) && SETTINGS_ENTRY_CLASS.equals(cls)) {
            if (amazonVisit != AmazonVisit.ESCAPE && lastHomePressedAt >= occurredAt) {
                // The receiver can run before the poll publishes this handler.
                // Do not re-latch Settings after the user's newer Home press.
                if (pending != null && !isHomeRequest(pending)) cancel("Settings before Home signal");
                returnFromSettings(lastHomePressedAt);
                return;
            }
            if (amazonVisit != AmazonVisit.ESCAPE) {
                amazonVisit = AmazonVisit.SETTINGS;
                settingsEnteredAt = occurredAt;
            }
            cancel("explicit Settings request");
            log.event("settings_visit_preserved", pkg, cls,
                    "ACTION_SETTINGS handler observed; Amazon settings visit preserved");
            return;
        }
        if (hud && homeEnabled && escapeLongPressEnabled) {
            log.event("hud_inferred", pkg, cls, "Previous activity: " + previous + "/" + previousClass);
            if (previous.equals(target) && !target.isEmpty()) {
                amazonVisit = AmazonVisit.ESCAPE;
                clearHomeSignals();
                cancel("HUD escape");
                schedule(new Request(HOME_PACKAGE, "hud_escape", true, now));
            } else if (isHome(previous, previousClass)) {
                amazonVisit = AmazonVisit.NONE;
                clearHomeSignals();
                scheduleTarget("hud_return", now + HOME_DELAY_MS);
            }
        } else if (isHome(pkg, cls) && homeEnabled && !isBypassing()) {
            log.event("home_inferred", pkg, cls, "Launcher arrival; physical key is not observable");
            scheduleTarget("home_inferred", now + HOME_DELAY_MS);
        }
    }

    private void expireSubmitted(long now) {
        if (submitted != null && now - submittedAt >= CONFIRM_TIMEOUT_MS) {
            retryHomeAfter = isHomeRequest(submitted)
                    && isHome(foregroundPackage, foregroundClass) && !isBypassing()
                    ? submittedAt + CONFIRM_TIMEOUT_MS : Long.MIN_VALUE;
            lastResult = "Launch unconfirmed: " + submitted.packageName;
            log.event("launch_unconfirmed", submitted.packageName, "", submitted.reason);
            if (coveredHome != null && coveredHome.id == submitted.id) cancelCover("launch unconfirmed");
            submitted = null;
            // A reused target activity may not emit a new foreground event on Fire OS.
            // Keep the result unconfirmed and do not retry an OS-queued start. A later
            // fresh Home/wake can still work; repeated requests retain circuit protection.
            if (!observationsAllowed) {
                lastResult = "Launch submitted; confirmation unavailable without Usage Access";
                log.event("confirmation_unavailable", "", "", lastResult);
            }
        }
    }

    /** Retrieve at most one eligible request; callers must report submission or failure. */
    Request poll(long now) {
        expireSubmitted(now);
        if (pending == null || pending.due > now || submitted != null) return null;
        Request next = pending;
        pending = null;
        if (!interactive || diagnosticOnly || tripped || target.isEmpty()) {
            cancelCover("request no longer eligible");
            return null;
        }
        if (isObservationRequest(next) && !observationsAllowed) {
            cancelCover("Usage Access unavailable");
            return null;
        }
        if (isHomeRequest(next)
                && (!isHome(foregroundPackage, foregroundClass) || isBypassing())) {
            cancelCover("Amazon visit no longer eligible");
            return null;
        }
        if (next.packageName.equals(foregroundPackage)
                && (!next.amazon || isHome(foregroundPackage, foregroundClass))) {
            cancelCover("target already foreground");
            return null;
        }
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= CIRCUIT_WINDOW_MS) {
            attempts.removeFirst();
        }
        if (attempts.size() >= 3) {
            suspend("Three automatic requests within 30 seconds");
            return null;
        }
        attempts.addLast(now);
        submitted = next;
        submittedAt = now;
        lastResult = "Launch requested: " + next.packageName;
        if (attempts.size() == 3) {
            tripped = true;
            log.event("circuit_open", "", "", "Third automatic request; explicit resume required");
            cancelCover("automatic suspension");
        }
        return next;
    }

    void failed(String detail) {
        String pkg = submitted == null ? "" : submitted.packageName;
        log.event("launch_failed", pkg, "", detail);
        submitted = null;
        lastResult = "Launch failed: " + detail;
        suspend("Activity launch failed");
    }

    void manualAmazon() {
        cancel("manual Amazon launch");
        amazonVisit = AmazonVisit.ESCAPE;
        clearHomeSignals();
    }

    void cancel(String why) {
        retryHomeAfter = Long.MIN_VALUE;
        if (pending != null) log.event("launch_cancelled", pending.packageName, "", why);
        pending = null;
        cancelCover(why);
        // Already-submitted starts cannot be withdrawn from Android's queue.
    }

    void suspend(String why) {
        tripped = true;
        cancel(why);
        log.event("automation_suspended", "", "", why);
    }

    private void scheduleTarget(String reason, long due) {
        schedule(new Request(target, reason, false, due));
    }

    private void schedule(Request request) {
        if (diagnosticOnly || tripped || target.isEmpty()) {
            log.event("would_launch", request.packageName, "", request.reason
                    + "; diagnostic, suspended, or no target");
            return;
        }
        if (submitted != null) return;
        if (pending != null && isHomeRequest(pending) && isHomeRequest(request)) return;
        // Wake/boot requests already cover a subsequent Amazon launcher arrival.
        if (pending != null && !isObservationRequest(pending)
                && "home_inferred".equals(request.reason)) return;
        cancelCover("request replaced");
        pending = new Request(request.packageName, request.reason, request.amazon, request.due,
                ++requestSequence);
        log.event("launch_scheduled", request.packageName, "", request.reason + "; due=" + request.due);
        if (cover != null && isHomeRequest(pending)) {
            coveredHome = pending;
            coverObserved = false;
            cover.show(pending.id, pending.packageName);
        }
    }

    private void cancelCover(String reason) {
        if (coveredHome == null) return;
        cover.cancel(coveredHome.id, reason);
        coveredHome = null;
        coverObserved = false;
    }

    private static boolean isObservationRequest(Request r) {
        return r.reason.startsWith("hud_") || isHomeRequest(r);
    }

    private boolean isPreHomeSettings(Request request, String pkg, String cls, long occurredAt) {
        return "settings_home".equals(request.reason) && SETTINGS_PACKAGE.equals(pkg)
                && !isHud(pkg, cls) && lastHomePressedAt != Long.MIN_VALUE
                && occurredAt <= lastHomePressedAt;
    }

    private static boolean isHomeRequest(Request r) {
        return "home_inferred".equals(r.reason) || "settings_home".equals(r.reason)
                || "home_recovery".equals(r.reason);
    }

    static boolean isHome(String pkg, String cls) {
        return HOME_PACKAGE.equals(pkg) && HOME_CLASS.equals(cls);
    }
    static boolean isHud(String pkg, String cls) {
        return SETTINGS_PACKAGE.equals(pkg) && HUD_CLASS.equals(cls);
    }
    private static boolean isAmazonVisit(String pkg) {
        return HOME_PACKAGE.equals(pkg) || SETTINGS_PACKAGE.equals(pkg);
    }
    boolean isTripped() { return tripped; }
    boolean isBypassing() { return amazonVisit != AmazonVisit.NONE; }
    String foreground() { return foregroundPackage + "/" + foregroundClass; }
    String lastResult() { return lastResult; }
    String pendingReason() { return pending == null ? "none" : pending.reason; }
}
