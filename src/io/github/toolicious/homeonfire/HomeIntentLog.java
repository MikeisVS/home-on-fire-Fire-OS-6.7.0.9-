// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; updated on 2026-11-14; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, metadata-only recognition of the system Home launch observed on AFTA. */
final class HomeIntentLog {
    static final long MAX_AGE_MS = 10000;
    private static final Pattern LINE = Pattern.compile(
            "^\\s*(\\d{9,12})\\.(\\d{3,6})\\s+1000\\s+\\d+\\s+\\d+\\s+I\\s+"
            + "ActivityManager\\s*:\\s+START u0 \\{([^\\r\\n]*)\\} from uid 1000 on display 0\\s*$");
    private static final Pattern ACTION = Pattern.compile("(?:^| )act=android\\.intent\\.action\\.MAIN(?: |$)");
    private static final Pattern CATEGORY = Pattern.compile("(?:^| )cat=\\[android\\.intent\\.category\\.HOME\\](?: |$)");
    private static final Pattern COMPONENT = Pattern.compile(
            "(?:^| )cmp=com\\.amazon\\.tv\\.launcher/(?:com\\.amazon\\.tv\\.launcher)?"
            + "\\.ui\\.HomeActivity_vNext(?: |$)");
    private static final Pattern FLAGS = Pattern.compile("(?:^| )flg=0x([0-9a-fA-F]{1,8})(?: |$)");
    private long armedWall, offset, lastAcceptedWall = Long.MIN_VALUE;

    HomeIntentLog(long wall, long elapsed) {
        armedWall = wall;
        offset = wall - elapsed;
    }

    /** Returns the event's elapsed time, or -1. Never returns or retains log content. */
    long accept(String line, long wall, long elapsed) {
        if (Math.abs((wall - elapsed) - offset) > 2000) {
            // Re-arm after a clock correction; never replay buffered pre-change Home.
            armedWall = wall;
            offset = wall - elapsed;
            lastAcceptedWall = Long.MIN_VALUE;
            return -1;
        }
        if (line == null || line.length() > 4096) return -1;
        Matcher match = LINE.matcher(line);
        if (!match.matches()) return -1;
        String intent = match.group(3);
        if (!ACTION.matcher(intent).find() || !CATEGORY.matcher(intent).find()
                || !COMPONENT.matcher(intent).find()) return -1;
        Matcher flags = FLAGS.matcher(intent);
        if (!flags.find()) return -1;
        // NEW_TASK | RESET_TASK_IF_NEEDED: the system Home path, not Settings' handover.
        if ((Long.parseLong(flags.group(1), 16) & 0x10200000L) != 0x10200000L) return -1;
        long eventWall = Long.parseLong(match.group(1)) * 1000
                + Long.parseLong(match.group(2).substring(0, 3));
        long age = wall - eventWall;
        if (eventWall <= armedWall || eventWall <= lastAcceptedWall
                || age < 0 || age > MAX_AGE_MS || age > elapsed) return -1;
        lastAcceptedWall = eventWall;
        return elapsed - age;
    }
}
