// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

/** Main-thread presentation state, independently testable without Android windows. */
final class HomeCoverState {
    static final long HOLD_MS = 700;
    static final long FADE_MS = 100;
    static final long WATCHDOG_MS = 10000;
    enum Phase { IDLE, WAITING, HOLDING, FADING }
    enum Action { NONE, FADE, REMOVE }

    private long latestId, activeId, deadline, fadeAt, removeAt;
    private boolean closed;
    private Phase phase = Phase.IDLE;

    boolean start(long id, long now) {
        // Retain retired identities so late shows cannot resurrect a cancelled cover.
        if (closed || id <= latestId) return false;
        latestId = activeId = id;
        deadline = now + WATCHDOG_MS;
        fadeAt = Long.MAX_VALUE;
        removeAt = deadline;
        phase = Phase.WAITING;
        return true;
    }

    boolean targetObserved(long id, long now) {
        if (closed || id != activeId || phase != Phase.WAITING || now >= deadline) return false;
        fadeAt = Math.min(deadline, now + HOLD_MS);
        removeAt = Math.min(deadline, fadeAt + FADE_MS);
        phase = Phase.HOLDING;
        return true;
    }

    boolean cancel(long id) {
        if (id < latestId) return false;
        latestId = id;
        if (activeId == 0 || id < activeId) return false;
        dismiss();
        return true;
    }

    void dismiss() { activeId = 0; phase = Phase.IDLE; }
    void close() { closed = true; dismiss(); }

    Action advance(long now) {
        if (phase == Phase.IDLE) return Action.NONE;
        if (now >= removeAt) {
            dismiss();
            return Action.REMOVE;
        }
        if (phase == Phase.HOLDING && now >= fadeAt) {
            phase = Phase.FADING;
            return Action.FADE;
        }
        return Action.NONE;
    }

    long nextBoundary() { return phase == Phase.HOLDING ? fadeAt : removeAt; }
    long fadeDuration(long now) { return Math.max(0, Math.min(FADE_MS, removeAt - now)); }
    long id() { return activeId; }
    long watchdogDeadline() { return deadline; }
    Phase phase() { return phase; }
}
