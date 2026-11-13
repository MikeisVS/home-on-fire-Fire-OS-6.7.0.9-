// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import java.util.concurrent.atomic.AtomicLong;

/** API-25 application overlay. All window, animation and state work stays on main. */
@SuppressWarnings("deprecation")
final class HomeCover implements RedirectEngine.CoverListener {
    static volatile String status = "Cover idle";
    private final Context context;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HomeCoverState state = new HomeCoverState();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean closed;
    private WindowManager windows;
    private LoaderView view;
    private String target = "";

    HomeCover(Context context, Prefs prefs) {
        this.context = context;
        this.prefs = prefs;
        status = "Cover idle";
    }

    private void post(final Runnable task) {
        final long version = generation.get();
        main.post(new Runnable() {
            @Override public void run() {
                if (!closed && version == generation.get()) task.run();
            }
        });
    }

    @Override public void show(final long id, final String pkg) {
        post(new Runnable() {
            @Override public void run() { attach(id, pkg); }
        });
    }

    @Override public void targetObserved(final long id) {
        post(new Runnable() {
            @Override public void run() {
                if (state.targetObserved(id, SystemClock.elapsedRealtime())) {
                    status = "Cover holding after launcher observation";
                    event("cover_target_observed", id, "Opaque hold 700 ms; fade 100 ms");
                    scheduleTick();
                }
            }
        });
    }

    @Override public void cancel(final long id, final String reason) {
        post(new Runnable() {
            @Override public void run() {
                if (state.cancel(id)) remove(id, reason);
            }
        });
    }

    /** Invalidate already queued window work before a configuration/lifecycle change. */
    void clear(final String reason) {
        generation.incrementAndGet();
        post(new Runnable() {
            @Override public void run() {
                long id = state.id();
                state.dismiss();
                if (id != 0 || view != null) remove(id, reason);
            }
        });
    }

    void destroy() {
        closed = true;
        generation.incrementAndGet();
        main.removeCallbacksAndMessages(null);
        // Service.onDestroy runs on main; no worker can attach a window after this.
        long id = state.id();
        state.close();
        if (id != 0 || view != null) remove(id, "service destroyed");
    }

    private boolean enabled() {
        return Backend.isAfta() && prefs.usesUsageBackend() && prefs.isMonitorEnabled()
                && prefs.isHomeCoverEnabled() && prefs.isHijackEnabled()
                && Backend.hasUsageAccess(context)
                && !prefs.isDiagnosticOnly() && !prefs.isAutomationSuspended();
    }

    private void attach(long id, String pkg) {
        if (!state.start(id, SystemClock.elapsedRealtime())) return;
        detachWindow();
        target = pkg;
        if (!enabled()) {
            state.cancel(id);
            status = "Cover disabled";
            return;
        }
        if (!Backend.hasOverlayPermission(context)) {
            unavailable(id, "Display over other apps permission is not allowed");
            return;
        }
        try {
            windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (windows == null) throw new IllegalStateException("WindowManager unavailable");
            view = new LoaderView(context);
            view.setBlackScreen(false);
            view.setLooping(false);
            String loading = context.getString(R.string.loader_caption_loading);
            // The animation completing is not evidence that the launcher is ready.
            view.setCaptions(loading, loading);
            view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT);
            layout.setTitle("HomeOnFireAftaCover");
            windows.addView(view, layout);
            status = "Cover showing while launcher opens";
            event("cover_shown", id, "TYPE_PHONE; nonfocusable; nontouchable; watchdog 10000 ms");
            scheduleTick();
        } catch (RuntimeException ex) {
            unavailable(id, ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void unavailable(long id, String detail) {
        state.cancel(id);
        detachWindow();
        status = "Cover unavailable: " + detail;
        event("cover_unavailable", id, detail);
    }

    private void scheduleTick() {
        main.removeCallbacks(tick);
        if (state.id() != 0) main.postDelayed(tick,
                Math.max(0, Math.min(250, state.nextBoundary() - SystemClock.elapsedRealtime())));
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed || state.id() == 0) return;
            long id = state.id();
            if (!enabled() || !Backend.hasOverlayPermission(context)) {
                boolean allowed = Backend.hasOverlayPermission(context);
                state.cancel(id);
                remove(id, allowed ? "cover or automation disabled" : "overlay permission lost");
                if (!allowed) status = "Cover unavailable: display over other apps permission lost";
                return;
            }
            long now = SystemClock.elapsedRealtime();
            boolean watchdog = now >= state.watchdogDeadline();
            HomeCoverState.Action action = state.advance(now);
            if (action == HomeCoverState.Action.REMOVE) {
                remove(id, watchdog ? "watchdog timeout" : "target observed; fade completed");
            } else if (action == HomeCoverState.Action.FADE && view != null) {
                status = "Cover fading into launcher";
                event("cover_fade", id, "Target observed; duration=" + state.fadeDuration(now) + " ms");
                view.animate().alpha(0f).setDuration(state.fadeDuration(now)).withEndAction(new Runnable() {
                    @Override public void run() {
                        if (!closed && state.cancel(id)) remove(id, "target observed; fade completed");
                    }
                }).start();
                scheduleTick();
            } else {
                scheduleTick();
            }
        }
    };

    private void remove(long id, String reason) {
        main.removeCallbacks(tick);
        detachWindow();
        status = "Cover cleared: " + reason;
        event("cover_removed", id, reason);
        target = "";
    }

    private void detachWindow() {
        if (view == null) return;
        view.animate().cancel();
        view.stop();
        try {
            if (windows != null) windows.removeViewImmediate(view);
        } catch (IllegalArgumentException ex) {
            // addView may have failed before attaching, or permission loss removed it.
        }
        view = null;
    }

    private void event(String name, long id, String detail) {
        DiagnosticLog.event(context, name, target, "", "request=" + id + "; " + detail);
    }
}
