// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

/** One background-kill request per fresh Couchy visit; independent of Android. */
final class AmazonCleanupPolicy {
    static final String AMAZON_PACKAGE = "com.amazon.tv.launcher";
    static final String COUCHY_PACKAGE = "com.conreo.couchytv";
    static final long STABILITY_MS = 1000;

    interface Log { void event(String event, String detail); }
    /** The Android adapter can kill only the fixed Amazon package, never a supplied target. */
    interface Cleaner { void killAmazonBackground(); }
    enum Result { NONE, REQUESTED, FAILED }

    private final Log log;
    private final Cleaner cleaner;
    private boolean configured, eligible, usedVisit;
    private String foregroundPackage = "", foregroundClass = "";
    private long sequence, pendingId, due;
    private String status = "Cleanup off";

    AmazonCleanupPolicy(Log log, Cleaner cleaner) {
        this.log = log;
        this.cleaner = cleaner;
    }

    void configure(boolean enabled, String selectedTarget) {
        reset("configuration changed");
        configured = enabled && COUCHY_PACKAGE.equals(selectedTarget);
        eligible = false;
        status = !enabled ? "Cleanup off" : configured
                ? "Awaiting a fresh Couchy arrival" : "Cleanup unavailable: select Couchy";
    }

    void eligibility(boolean allowed, String reason) {
        eligible = configured && allowed;
        if (!eligible) cancel(reason);
    }

    void reset(String reason) {
        cancel(reason);
        foregroundPackage = foregroundClass = "";
        usedVisit = false;
    }

    /** Observations never call Android; poll only after consuming the complete event batch. */
    void observe(String pkg, String cls, boolean foreground, boolean fresh, long now) {
        pkg = pkg == null ? "" : pkg;
        cls = cls == null ? "" : cls;
        if (!foreground) {
            if (pkg.equals(foregroundPackage) && cls.equals(foregroundClass)) {
                cancel("Couchy left foreground");
                foregroundPackage = foregroundClass = "";
                usedVisit = false;
            }
            return;
        }
        boolean wasCouchy = COUCHY_PACKAGE.equals(foregroundPackage);
        foregroundPackage = pkg;
        foregroundClass = cls;
        if (!COUCHY_PACKAGE.equals(pkg)) {
            cancel("another activity appeared");
            usedVisit = false;
            return;
        }
        if (!fresh) {
            cancel("historical observation");
            usedVisit = true; // A seeded or stale foreground is not a new Couchy visit.
            return;
        }
        if (wasCouchy || usedVisit || !configured || !eligible) return;
        pendingId = ++sequence;
        due = now + STABILITY_MS;
        status = "Waiting for Couchy to remain foreground for one second";
        log.event("cleanup_scheduled", "id=" + pendingId + "; stabilityMs=" + STABILITY_MS);
    }

    Result poll(long now) {
        if (!configured || !eligible || pendingId == 0 || now < due
                || !COUCHY_PACKAGE.equals(foregroundPackage)) return Result.NONE;
        long id = pendingId;
        pendingId = 0;
        usedVisit = true;
        log.event("cleanup_attempt", "id=" + id + "; Android background-process kill; not force-stop");
        try {
            cleaner.killAmazonBackground();
            status = "Background kill requested; RAM saving unverified";
            log.event("cleanup_requested", "id=" + id + "; void API returned; process exit and savings unverified");
            return Result.REQUESTED;
        } catch (RuntimeException ex) {
            configured = eligible = false;
            status = "Cleanup failed and disabled: " + ex.getClass().getSimpleName();
            log.event("cleanup_failed", "id=" + id + "; " + ex.toString());
            return Result.FAILED;
        }
    }

    private void cancel(String reason) {
        if (pendingId == 0) return;
        log.event("cleanup_cancelled", "id=" + pendingId + "; " + reason);
        pendingId = 0;
        status = "Cleanup cancelled: " + reason;
    }

    String status() { return status; }
}
