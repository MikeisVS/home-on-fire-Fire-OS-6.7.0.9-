// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Opt-in, API-25/AFTA-only foreground monitor. Android event reads and policy changes
 * are serialized on one worker thread. Home loading windows are confined to main.
 * No key injection or privileged APIs.
 */
@SuppressWarnings("deprecation")
public final class UsageMonitorService extends Service {
    static final String CONFIGURE = "configure";
    static final String RESUME = "resume";
    static final String BOOT = "boot";
    static final String OPEN_TARGET = "open_target";
    static final String OPEN_AMAZON = "open_amazon";
    // Existing nonexported CONFIGURE action; usable only by this app's UID.
    static final String EXTRA_AMAZON_CLEANUP = "amazon_cleanup_enabled";
    static volatile boolean running;
    static volatile long lastPollElapsed;
    static volatile String status = "Monitor stopped";
    static volatile String foreground = "Unknown";
    static volatile String launchStatus = "No launch requested";
    static volatile String cleanupStatus = "Cleanup off";
    private HandlerThread thread;
    private Handler worker;
    private Prefs prefs;
    private RedirectEngine engine;
    private AmazonCleanupPolicy cleanup;
    private HomeCover homeCover;
    private final UsageCursor cursor = new UsageCursor();
    private PowerManager power;
    private UsageStatsManager usage;
    private boolean awake, dreaming, permission, conflict;
    private volatile boolean destroyed;
    private boolean receiverRegistered;
    private final Object dispatchLock = new Object();
    private String configured = "";

    static void start(Context ctx, String action) {
        if (!Backend.isAfta() || !new Prefs(ctx).usesUsageBackend()) return;
        Intent intent = new Intent(ctx, UsageMonitorService.class).setAction(action);
        try {
            // This backend is deliberately unavailable on API 26+.
            if (ctx.startService(intent) == null) throw new IllegalStateException("Service start returned null");
        } catch (RuntimeException ex) {
            status = "Monitor start failed: " + ex.getClass().getSimpleName();
            DiagnosticLog.event(ctx, "service_start_failed", "", "", status);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        if (!Backend.isAfta() || !prefs.usesUsageBackend() || !prefs.isMonitorEnabled()) {
            stopSelf();
            return;
        }
        power = (PowerManager) getSystemService(POWER_SERVICE);
        usage = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        homeCover = new HomeCover(this, prefs);
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, AftaSettingsActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Home on Fire AFTA")
                .setContentText("Experimental monitor — open to inspect or stop")
                .setContentIntent(open).setOngoing(true).build();
        try {
            startForeground(25, notification);
            running = true;
            thread = new HandlerThread("AftaUsageMonitor");
            thread.start();
            worker = new Handler(thread.getLooper());
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_DREAMING_STARTED);
            filter.addAction(Intent.ACTION_DREAMING_STOPPED);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            registerReceiver(screenReceiver, filter);
            receiverRegistered = true;
        } catch (RuntimeException ex) {
            destroyed = true;
            running = false;
            status = "Monitor initialization failed: " + ex.getClass().getSimpleName();
            prefs.setAutomationSuspended(true);
            DiagnosticLog.event(this, "service_initialization_failed", "", "", ex.toString());
            stopSelf();
            return;
        }
        worker.post(new Runnable() {
            @Override public void run() {
                engine = new RedirectEngine(new RedirectEngine.Log() {
                    @Override public void event(String event, String pkg, String cls, String detail) {
                        DiagnosticLog.event(UsageMonitorService.this, event, pkg, cls, detail);
                    }
                }, homeCover);
                cleanup = new AmazonCleanupPolicy(new AmazonCleanupPolicy.Log() {
                    @Override public void event(String event, String detail) {
                        DiagnosticLog.event(UsageMonitorService.this, event,
                                AmazonCleanupPolicy.AMAZON_PACKAGE, "", detail);
                    }
                }, new AmazonCleanupPolicy.Cleaner() {
                    @Override public void killAmazonBackground() {
                        if (!Backend.isAfta()
                                || !AmazonCleanupPolicy.COUCHY_PACKAGE.equals(prefs.getTargetPackage())) {
                            throw new IllegalStateException("Cleanup is restricted to AFTA/API25 and Couchy");
                        }
                        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                        if (manager == null) throw new IllegalStateException("ActivityManager unavailable");
                        manager.killBackgroundProcesses(AmazonCleanupPolicy.AMAZON_PACKAGE);
                    }
                });
                configure(false);
                awake = power != null && power.isInteractive();
                engine.screen(awake, false, SystemClock.elapsedRealtime());
                DiagnosticLog.event(UsageMonitorService.this, "service_started", "", "",
                        AppInfo.identity(UsageMonitorService.this) + "; accessibility/key events unavailable in Usage mode");
                schedulePoll();
            }
        });
    }

    @Override public int onStartCommand(final Intent intent, int flags, int startId) {
        if (worker == null || destroyed || !prefs.isMonitorEnabled() || !prefs.usesUsageBackend()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        worker.post(new Runnable() {
            @Override public void run() {
                if (destroyed) return;
                String action = intent == null ? "" : intent.getAction();
                if (CONFIGURE.equals(action) && intent.hasExtra(EXTRA_AMAZON_CLEANUP)) {
                    prefs.setAmazonCleanupEnabled(intent.getBooleanExtra(EXTRA_AMAZON_CLEANUP, false));
                }
                if (CONFIGURE.equals(action) || RESUME.equals(action)) configure(RESUME.equals(action));
                if (BOOT.equals(action)) {
                    DiagnosticLog.event(UsageMonitorService.this, "boot_monitor_request", "", "", "BOOT_COMPLETED");
                    engine.boot(SystemClock.elapsedRealtime());
                } else if (OPEN_AMAZON.equals(action)) {
                    cleanup.reset("manual Amazon launch");
                    engine.manualAmazon();
                    manualLaunch(RedirectEngine.HOME_PACKAGE, true);
                } else if (OPEN_TARGET.equals(action)) {
                    engine.cancel("manual target launch");
                    manualLaunch(prefs.getTargetPackage(), false);
                }
                schedulePoll();
            }
        });
        return START_STICKY;
    }

    private void manualLaunch(String pkg, boolean amazon) {
        String error = TargetLauncher.launch(this, pkg, amazon, "manual");
        launchStatus = error == null ? "Manual launch submitted: " + pkg : error;
    }

    private void configure(boolean resume) {
        homeCover.clear("configuration changed");
        if (resume) prefs.setAutomationSuspended(false);
        engine.configure(prefs.getTargetPackage(), prefs.isHijackEnabled(), prefs.getLaunchOnBoot(),
                prefs.getLaunchOnWake(), prefs.isDiagnosticOnly(), prefs.isAutomationSuspended(),
                prefs.isEscapeLongPress());
        if (resume) engine.resume();
        cleanup.configure(prefs.isAmazonCleanupEnabled(), prefs.getTargetPackage());
        cleanupStatus = cleanup.status();
        cursor.reset(System.currentTimeMillis());
        configured = configurationKey();
        DiagnosticLog.event(this, "configuration", "", "",
                "diagnosticOnly=" + prefs.isDiagnosticOnly() + "; home=" + prefs.isHijackEnabled()
                + "; boot=" + prefs.getLaunchOnBoot() + "; wake=" + prefs.getLaunchOnWake()
                + "; homeCover=" + prefs.isHomeCoverEnabled()
                + "; amazonCleanup=" + prefs.isAmazonCleanupEnabled()
                + "; escapeLongPress=" + prefs.isEscapeLongPress());
    }

    private String configurationKey() {
        return prefs.getTargetPackage() + "|" + prefs.isHijackEnabled() + "|"
                + prefs.getLaunchOnBoot() + "|" + prefs.getLaunchOnWake() + "|" + prefs.isDiagnosticOnly()
                + "|" + prefs.isHomeCoverEnabled() + "|" + prefs.isAmazonCleanupEnabled()
                + "|" + prefs.isEscapeLongPress();
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, final Intent intent) {
            if (homeCover != null && (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())
                    || Intent.ACTION_DREAMING_STARTED.equals(intent.getAction()))) {
                homeCover.clear("screen off or standby");
            }
            if (worker == null || destroyed) return;
            worker.post(new Runnable() {
                @Override public void run() {
                    if (destroyed) return;
                    String action = intent.getAction();
                    DiagnosticLog.event(UsageMonitorService.this, "screen_broadcast", "", "", action);
                    if (Intent.ACTION_TIME_CHANGED.equals(action)) {
                        cleanup.reset("wall clock changed");
                        cursor.reset(System.currentTimeMillis());
                        engine.resetObservation("wall clock changed");
                    } else {
                        if (Intent.ACTION_DREAMING_STARTED.equals(action)) dreaming = true;
                        if (Intent.ACTION_DREAMING_STOPPED.equals(action)
                                || Intent.ACTION_SCREEN_OFF.equals(action)
                                || Intent.ACTION_SCREEN_ON.equals(action)) dreaming = false;
                        boolean on = !Intent.ACTION_SCREEN_OFF.equals(action)
                                && !dreaming && power != null && power.isInteractive();
                        updateScreen(on);
                    }
                    schedulePoll();
                    // Some firmware emits SCREEN_ON just before isInteractive changes.
                    if (Intent.ACTION_SCREEN_ON.equals(action) && !awake) {
                        worker.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (destroyed) return;
                                updateScreen(!dreaming && power != null && power.isInteractive());
                                schedulePoll();
                            }
                        }, 500);
                    }
                }
            });
        }
    };

    private void updateScreen(boolean on) {
        if (awake == on) return;
        awake = on;
        cleanup.reset(on ? "new wake observation" : "screen off or standby");
        if (!on) homeCover.clear("screen off or standby");
        cursor.reset(System.currentTimeMillis());
        engine.screen(on, true, SystemClock.elapsedRealtime());
        DiagnosticLog.event(this, on ? "wake" : "standby", "", "", "interactive=" + on);
    }

    private void schedulePoll() {
        worker.removeCallbacks(poll);
        if (awake && !destroyed) worker.post(poll);
        else status = "Monitor sleeping; UsageEvents polling suspended";
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            if (!prefs.isMonitorEnabled() || !prefs.usesUsageBackend()) {
                cleanup.reset("monitor disabled");
                engine.cancel("monitor disabled");
                stopSelf();
                return;
            }
            if (!configured.equals(configurationKey())) configure(false);
            updateScreen(!dreaming && power != null && power.isInteractive());
            if (!awake) {
                cleanup.eligibility(false, "standby");
                status = "Monitor sleeping; UsageEvents polling suspended";
                return;
            }
            lastPollElapsed = SystemClock.elapsedRealtime();
            boolean allowed = Backend.hasUsageAccess(UsageMonitorService.this);
            if (allowed != permission) {
                permission = allowed;
                engine.permission(allowed);
                cursor.reset(System.currentTimeMillis());
                DiagnosticLog.event(UsageMonitorService.this, "usage_permission", "", "", "allowed=" + allowed);
            }
            boolean competing = Backend.otherHomeOnFireEnabled(UsageMonitorService.this);
            if (competing && !conflict) engine.suspend("Another Home on Fire accessibility service is enabled");
            conflict = competing;
            cleanup.eligibility(cleanupAllowed(allowed), "cleanup no longer eligible");
            boolean queryHealthy = true;
            if (allowed) queryHealthy = readEvents();
            synchronized (dispatchLock) {
                if (destroyed || !prefs.isMonitorEnabled()) return;
                if (!configured.equals(configurationKey())) configure(false);
                if (queryHealthy && !conflict) {
                    RedirectEngine.Request request = engine.poll(SystemClock.elapsedRealtime());
                    if (request != null) {
                        String error = TargetLauncher.launch(UsageMonitorService.this,
                                request.packageName, request.amazon, request.reason);
                        if (error != null) engine.failed(error);
                    }
                }
                // Android starts may open the circuit; recheck all gates after the full batch.
                cleanup.eligibility(queryHealthy && cleanupAllowed(allowed), "cleanup no longer eligible");
                if (cleanup.poll(SystemClock.elapsedRealtime()) == AmazonCleanupPolicy.Result.FAILED) {
                    prefs.setAmazonCleanupEnabled(false);
                    // Only cleanup changed; do not reset or suspend normal redirection.
                    configured = configurationKey();
                }
                cleanupStatus = cleanup.status();
            }
            if (engine.isTripped() && !prefs.isAutomationSuspended()) prefs.setAutomationSuspended(true);
            foreground = engine.foreground();
            launchStatus = engine.lastResult();
            status = (prefs.isDiagnosticOnly() ? "Diagnostic only" : "Automation enabled")
                    + (engine.isTripped() ? " — SUSPENDED; explicit resume required" : "")
                    + (conflict ? " — competing Home on Fire service" : "")
                    + (allowed ? (queryHealthy ? " — Usage Access allowed" : " — event query failed")
                               : " — Usage Access unavailable; Home/HUD detection disabled")
                    + "; pending=" + engine.pendingReason()
                    + (engine.isBypassing() ? "; Amazon visit preserved" : "");
            if (!destroyed) worker.postDelayed(this, allowed && queryHealthy ? 250 : 1000);
        }
    };

    private boolean cleanupAllowed(boolean allowed) {
        return Backend.isAfta() && prefs.usesUsageBackend() && prefs.isMonitorEnabled()
                && prefs.isAmazonCleanupEnabled() && !prefs.isDiagnosticOnly()
                && !prefs.isAutomationSuspended() && !engine.isTripped()
                && !engine.isBypassing()
                && !conflict && allowed && awake && !destroyed
                && !dreaming && power != null && power.isInteractive();
    }

    private boolean readEvents() {
        long wall = System.currentTimeMillis();
        long now = SystemClock.elapsedRealtime();
        long begin = cursor.begin(wall, now);
        boolean seeding = cursor.isSeeding();
        if (seeding) {
            engine.resetObservation("seed recent history without launching");
            cleanup.reset("seed recent history without cleanup");
        }
        try {
            if (usage == null) throw new IllegalStateException("UsageStatsManager unavailable");
            UsageEvents events = usage.queryEvents(begin, wall);
            if (events == null) throw new IllegalStateException("queryEvents returned null");
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                String pkg = event.getPackageName(), cls = event.getClassName();
                long timestamp = event.getTimeStamp();
                int type = event.getEventType();
                if (!cursor.accept(timestamp, type, pkg, cls)) continue;
                boolean fresh = !seeding && cursor.isFresh(timestamp);
                if (fresh) DiagnosticLog.event(this, "usage_event", pkg, cls,
                        "type=" + type + "; eventWallTimeMs=" + timestamp
                        + "; queryEndWallTimeMs=" + wall + "; ageAtQueryMs=" + Math.max(0, wall - timestamp));
                if (type == UsageEvents.Event.MOVE_TO_FOREGROUND || type == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    if (!cursor.acceptObservation(timestamp)) {
                        if (fresh) DiagnosticLog.event(this, "usage_observation_ignored", pkg, cls,
                                "Older than the last observed transition; eventWallTimeMs=" + timestamp);
                        continue;
                    }
                    long occurredAt = now - Math.max(0, wall - timestamp);
                    engine.observe(pkg, cls, type == UsageEvents.Event.MOVE_TO_FOREGROUND,
                            fresh, now, occurredAt);
                    cleanup.observe(pkg, cls, type == UsageEvents.Event.MOVE_TO_FOREGROUND, fresh, now);
                }
            }
            cursor.finish();
            if (seeding) DiagnosticLog.event(this, "foreground_seed", "", "", engine.foreground());
            return true;
        } catch (RuntimeException ex) {
            cleanup.reset("event query failed");
            cursor.reset(wall);
            engine.resetObservation("event query failed");
            engine.suspend("UsageEvents query failed: " + ex.getClass().getSimpleName());
            DiagnosticLog.event(this, "usage_query_failed", "", "", ex.toString());
            return false;
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        synchronized (dispatchLock) { destroyed = true; }
        if (homeCover != null) homeCover.destroy();
        running = false;
        if (!status.startsWith("Monitor initialization failed")) status = "Monitor stopped";
        if (receiverRegistered) unregisterReceiver(screenReceiver);
        if (worker != null) {
            worker.removeCallbacksAndMessages(null);
            // Policy stays on its worker; quitSafely runs this already-due cancellation.
            worker.post(new Runnable() {
                @Override public void run() {
                    if (cleanup != null) {
                        cleanup.reset("service stopped");
                        cleanupStatus = cleanup.status();
                    }
                }
            });
        }
        if (thread != null) thread.quitSafely();
        DiagnosticLog.event(this, "service_stopped", "", "", "Pending local work discarded");
        stopForeground(true);
        super.onDestroy();
    }
}
