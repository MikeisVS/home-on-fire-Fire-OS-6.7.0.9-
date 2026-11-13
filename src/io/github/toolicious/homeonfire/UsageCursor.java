// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Overlapping wall-clock UsageEvents queries with a monotonic clock-change check. */
final class UsageCursor {
    // Allow late firmware publication as well as overlapping client polls.
    static final long OVERLAP_MS = 10000;
    private final LinkedHashMap<String, Long> seen = new LinkedHashMap<String, Long>();
    private long lastWall, lastElapsed, freshAfter, end;
    private long lastObservation = Long.MIN_VALUE;
    private boolean initialized, seeding = true;

    long begin(long wall, long elapsed) {
        if (!initialized || Math.abs((wall - lastWall) - (elapsed - lastElapsed)) > 2000) {
            reset(wall);
        }
        end = wall;
        long start = seeding ? Math.max(0, wall - 86400000L)
                : Math.max(0, lastWall - OVERLAP_MS);
        lastWall = wall;
        lastElapsed = elapsed;
        initialized = true;
        return start;
    }

    boolean isSeeding() { return seeding; }
    boolean isFresh(long timestamp) { return timestamp > freshAfter; }
    boolean accept(long timestamp, int type, String pkg, String cls) {
        // Future events must not poison the cursor following a clock correction.
        if (timestamp > end) return false;
        String key = timestamp + "|" + type + "|" + pkg + "|" + cls;
        if (seen.containsKey(key)) return false;
        seen.put(key, timestamp);
        return true;
    }

    /** Late events may be logged, but must not replace a newer foreground state. */
    boolean acceptObservation(long timestamp) {
        if (timestamp < lastObservation) return false;
        lastObservation = timestamp;
        return true;
    }

    void finish() {
        seeding = false;
        Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < end - OVERLAP_MS) it.remove();
        }
    }

    void reset(long wall) {
        seen.clear();
        seeding = true;
        freshAfter = wall;
        lastObservation = Long.MIN_VALUE;
        initialized = false;
    }
}
