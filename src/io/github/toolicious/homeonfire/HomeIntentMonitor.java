// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; updated on 2026-11-14; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** AFTA-only log reader. READ_LOGS must be explicitly granted during installation. */
final class HomeIntentMonitor {
    interface Listener { void home(long occurredAt); }
    private final Context context;
    private final Handler worker;
    private final Listener listener;
    private Thread reader;
    private Process process;
    private long generation, retryAt;
    private String state = "stopped";

    HomeIntentMonitor(Context context, Handler worker, Listener listener) {
        this.context = context.getApplicationContext();
        this.worker = worker;
        this.listener = listener;
    }

    synchronized String status() { return state; }

    synchronized void update(boolean wanted) {
        if (!wanted) { stop(); return; }
        if (context.checkCallingOrSelfPermission(Manifest.permission.READ_LOGS)
                != PackageManager.PERMISSION_GRANTED) {
            stop();
            setState("READ_LOGS unavailable");
            return;
        }
        if (reader != null || SystemClock.elapsedRealtime() < retryAt) return;
        final long run = ++generation;
        final HomeIntentLog parser = new HomeIntentLog(System.currentTimeMillis(), SystemClock.elapsedRealtime());
        reader = new Thread(new Runnable() {
            @Override public void run() { read(run, parser); }
        }, "AftaHomeIntentLog");
        reader.start();
    }

    synchronized void stop() {
        generation++;
        if (process != null) process.destroy();
        process = null;
        reader = null;
        retryAt = 0;
        // Keep an unavailable/error explanation while already stopped.
        if ("listening".equals(state)) setState("stopped");
    }

    private synchronized boolean current(long run) { return generation == run; }

    private void read(final long run, HomeIntentLog parser) {
        Process child = null;
        try {
            // -T 1 starts with at most one old record; the parser rejects everything
            // at/before arming. -v uid supplies the logger's UID, not a message claim.
            child = new ProcessBuilder("/system/bin/logcat", "-b", "system", "-v", "epoch",
                    "-v", "uid", "-T", "1", "-s", "ActivityManager:I", "*:S")
                    .redirectErrorStream(true).start();
            synchronized (this) {
                if (!current(run)) return;
                process = child;
                setState("listening");
            }
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(child.getInputStream(), "UTF-8"))) {
                String line;
                while (current(run) && (line = lines.readLine()) != null) {
                    final long occurredAt = parser.accept(line, System.currentTimeMillis(), SystemClock.elapsedRealtime());
                    // No raw line, intent extras, other app activity, account or URI
                    // leaves this reader or is written to Home on Fire diagnostics.
                    if (occurredAt < 0) continue;
                    worker.post(new Runnable() {
                        @Override public void run() {
                            if (current(run) && SystemClock.elapsedRealtime() - occurredAt <= HomeIntentLog.MAX_AGE_MS) {
                                listener.home(occurredAt);
                            }
                        }
                    });
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // Diagnostics deliberately do not include log content or exception text.
        } finally {
            if (child != null) child.destroy();
            synchronized (this) {
                if (current(run)) {
                    process = null;
                    reader = null;
                    retryAt = SystemClock.elapsedRealtime() + 5000;
                    setState("reader unavailable; retry delayed");
                }
            }
        }
    }

    private void setState(String value) {
        if (value.equals(state)) return;
        state = value;
        DiagnosticLog.event(context, "home_log_status", "", "", value);
    }
}
