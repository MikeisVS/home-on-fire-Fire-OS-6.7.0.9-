// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; updated on 2026-11-14; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import java.util.Locale;

/** Fixtures match observed AFTA epoch+UID ActivityManager records, without private data. */
public final class HomeIntentLogTest {
    private static final long WALL = 1794640000000L, ELAPSED = 1000000;
    private static int checks;
    private static final String HOME = "act=android.intent.action.MAIN cat=[android.intent.category.HOME] "
            + "flg=0x10200000 cmp=com.amazon.tv.launcher/.ui.HomeActivity_vNext (has extras)";

    private static String line(long offset, String body, int loggerUid, int callerUid) {
        long time = WALL + offset;
        return String.format(Locale.ROOT, "%d.%03d  %d  3634  3678 I ActivityManager: START u0 {%s} from uid %d on display 0",
                time / 1000, time % 1000, loggerUid, body, callerUid);
    }
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static long parse(String record) {
        return new HomeIntentLog(WALL, ELAPSED).accept(record, WALL + 1000, ELAPSED + 1000);
    }

    public static void main(String[] args) {
        String home = line(100, HOME, 1000, 1000);
        check(parse(home) == ELAPSED + 100, "System Home maps the log timestamp to elapsed time");
        check(parse(line(100, HOME, 10216, 1000)) == -1, "A non-system logger cannot spoof the message's caller");
        check(parse(line(100, HOME, 1000, 10165)) == -1, "Settings app handover is not a remote Home cue");
        check(parse(line(100, HOME, 1000, 10213)) == -1, "An explicit Home on Fire Amazon launch is not a remote cue");
        check(parse(line(100, HOME, 1000, 2000)) == -1, "ADB am-start is not substituted for an input-driven system launch");
        check(parse(home.replace("  1000  3634", "  3634")) == -1, "Missing logger UID fails closed");
        check(parse(home.replace("ActivityManager:", "OtherTag:")) == -1, "Other log tags are ignored");
        check(parse(home.replace("START u0", "START u10")) == -1, "Other Android users are ignored");
        check(parse(home.replace("display 0", "display 1")) == -1, "Other displays are ignored");
        check(parse(home.replace("action.MAIN", "action.VIEW")) == -1, "A view intent cannot act as Home");
        check(parse(home.replace("category.HOME", "category.LAUNCHER")) == -1, "A launcher icon start is not Home");
        check(parse(home.replace("category.HOME]", "category.HOME,other]")) == -1, "Unexpected categories fail closed");
        check(parse(home.replace("0x10200000", "0x10000000")) == -1, "Settings' observed flags do not identify the system Home path");
        check(parse(home.replace("0x10200000", "0x10200100")) == ELAPSED + 100, "Additional flags retain required Home bits");
        check(parse(home.replace("com.amazon.tv.launcher/", "com.amazon.tv.launcher.fake/")) == -1, "Similar package names are not accepted");
        check(parse(home.replace(".ui.HomeActivity_vNext", ".ui.OtherActivity")) == -1, "Only the exact observed Home activity is accepted");
        check(parse(home.replace("/.ui.", "/com.amazon.tv.launcher.ui.")) == ELAPSED + 100, "Fully qualified equivalent component is accepted");
        check(parse(home + " trailing text") == -1, "Trailing text is not silently trusted");
        check(parse(null) == -1 && parse(new String(new char[4097])) == -1, "Absent or oversized log records are discarded");
        check(parse(line(0, HOME, 1000, 1000)) == -1, "The initial log tail cannot replay a pre-arming Home");
        check(parse(line(1001, HOME, 1000, 1000)) == -1, "Future records are ignored");
        HomeIntentLog parser = new HomeIntentLog(WALL, ELAPSED);
        check(parser.accept(home, WALL + 10100, ELAPSED + 10100) == ELAPSED + 100, "Ten-second delivery limit is inclusive");
        parser = new HomeIntentLog(WALL, ELAPSED);
        check(parser.accept(home, WALL + 10101, ELAPSED + 10101) == -1, "Older buffered Home cannot trigger navigation");
        parser = new HomeIntentLog(WALL, ELAPSED);
        check(parser.accept(home, WALL + 1000, ELAPSED + 1000) == ELAPSED + 100
                && parser.accept(home, WALL + 1001, ELAPSED + 1001) == -1, "An identical Home record is accepted once");
        check(parser.accept(line(99, HOME, 1000, 1000), WALL + 1002, ELAPSED + 1002) == -1,
                "A late older Home cannot replace the newer cue");
        parser = new HomeIntentLog(WALL, ELAPSED);
        check(parser.accept(line(100100, HOME, 1000, 1000), WALL + 100100, ELAPSED + 1000) == -1,
                "A clock jump re-arms without accepting the current buffered record");
        check(parser.accept(line(100101, HOME, 1000, 1000), WALL + 100102, ELAPSED + 1002) == ELAPSED + 1001,
                "A fresh post-correction Home is accepted on the new clock mapping");

        RedirectEngine engine = new RedirectEngine((event, pkg, cls, detail) -> {});
        engine.configure("com.conreo.couchytv", true, true, true, false, false);
        engine.permission(true);
        engine.screen(true, false, ELAPSED);
        engine.observe(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.SETTINGS_ENTRY_CLASS, true, true, ELAPSED + 10);
        engine.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, true, ELAPSED + 20);
        String settings = line(30, HOME.replace("flg=0x10200000 ", ""), 1000, 10165);
        check(parse(settings) == -1 && engine.isBypassing() && engine.poll(ELAPSED + 1000) == null,
                "Real Settings-handover fixture keeps its visit accessible");
        engine.homePressed("homekey", parse(home));
        RedirectEngine.Request request = engine.poll(ELAPSED + 5600);
        check(request != null && "settings_home".equals(request.reason) && request.due == ELAPSED + 5600,
                "Real Home fixture ends same-activity Settings with the existing cooldown");
        System.out.println("Home intent log: " + checks + " behavioral assertions passed");
    }
}
