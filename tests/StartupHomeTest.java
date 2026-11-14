// SPDX-License-Identifier: GPL-3.0-or-later
/* AFTA fork changes implemented with OpenAI Codex on 2026-11-12; updated on 2026-11-14; original Home on Fire by toolicious. */ package io.github.toolicious.homeonfire;

import java.util.ArrayList;
import java.util.List;

/** Boot deadlines and system Home cues exercised as observable event sequences. */
public final class StartupHomeTest {
    private static final String TARGET = "com.conreo.couchytv";
    private static int checks;

    private static final class Trace implements RedirectEngine.CoverListener {
        final List<String> events = new ArrayList<String>();
        final RedirectEngine engine = new RedirectEngine(
                (event, pkg, cls, detail) -> events.add(event), this);
        int shown, ready, cancelled;
        Trace() {
            engine.configure(TARGET, true, true, false, false, false);
            engine.permission(true);
            engine.screen(true, false, 0);
        }
        public void show(long id, String target) { shown++; }
        public void targetObserved(long id) { ready++; }
        public void cancel(long id, String reason) { cancelled++; }
        void home(long now) { foreground(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, now); }
        void target(long now) { foreground(TARGET, TARGET + ".MainActivity", now); }
        void entry(long now) { foreground(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.SETTINGS_ENTRY_CLASS, now); }
        void foreground(String pkg, String cls, long now) { engine.observe(pkg, cls, true, true, now); }
        void background(String pkg, String cls, long now) { engine.observe(pkg, cls, false, true, now); }
        void settings() {
            target(100);
            background(TARGET, TARGET + ".MainActivity", 125);
            entry(150);
            background(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.SETTINGS_ENTRY_CLASS, 175);
            home(200);
        }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static void settingsReturn(RedirectEngine.Request request, String label) {
        check(request != null && "settings_home".equals(request.reason)
                && TARGET.equals(request.packageName) && !request.amazon, label);
    }

    private static void bootTests() {
        Trace t = new Trace();
        t.engine.boot(100);
        t.engine.boot(120);
        RedirectEngine.Request request = t.engine.poll(120);
        check(request != null && "boot".equals(request.reason) && request.due == 100,
                "Boot is eligible on the first poll and duplicate boot does not postpone it");
        check(t.shown == 0, "Boot does not show the Home cover");
        check(t.engine.poll(121) == null, "Boot has only one submitted request");
        t.target(150);
        t.foreground("video.app", "Player", 200);
        t.engine.boot(250);
        check(t.engine.poll(10000) == null, "A duplicate boot cannot interrupt a later app");

        t = new Trace(); t.target(50); t.engine.boot(100);
        check(t.engine.poll(100) == null, "An already-foreground target satisfies boot");
        t = new Trace(); t.engine.screen(false, false, 0); t.engine.boot(100);
        check(t.engine.poll(20000) == null, "Boot waits while the screen is off");
        t.engine.screen(true, true, 21000);
        request = t.engine.poll(21000);
        check(request != null && "boot".equals(request.reason) && request.due == 21000,
                "Deferred boot runs immediately when interactive, without a second delay");
        t.engine.screen(true, true, 21001);
        check(t.engine.poll(21001) == null, "Overlapping wake does not duplicate deferred boot");

        t = new Trace(); t.engine.boot(100); t.engine.screen(false, false, 110);
        t.engine.screen(true, true, 200);
        check(t.engine.poll(200) != null, "Screen-off before submission preserves the one boot request");
        t = new Trace(); t.engine.permission(false); t.engine.boot(100);
        check(t.engine.poll(100) != null, "Boot retains the existing no-Usage-Access fallback");
        t.engine.poll(10100);
        check(!t.engine.isTripped() && t.engine.poll(20000) == null,
                "Unconfirmed boot is not retried or suspended merely for missing confirmation");
        t = new Trace(); t.engine.configure(TARGET, true, false, true, false, false);
        t.engine.screen(false, false, 0); t.engine.boot(100); t.engine.screen(true, true, 200);
        check(t.engine.poll(200) == null, "Disabled boot does not turn wake into an immediate launch");
        request = t.engine.poll(5700);
        check(request != null && "wake".equals(request.reason), "Wake keeps its existing delay");
        t = new Trace(); t.home(100);
        check(t.engine.poll(100) == null && t.engine.poll(5599) == null,
                "Ordinary Home still waits 5.5 seconds");
        check(t.engine.poll(5600) != null, "Ordinary Home remains eligible at its old deadline");
    }

    private static void settingsTests() {
        Trace t = new Trace(); t.settings();
        check(t.engine.isBypassing() && t.engine.poll(10000) == null && t.shown == 0,
                "Opening Settings stays accessible without a cover or timeout redirect");
        t.engine.homePressed("homekey", 11000);
        check(!t.engine.isBypassing(), "A real Home cue ends Settings preservation");
        check(t.engine.poll(16499) == null, "Settings return respects the Home cooldown");
        settingsReturn(t.engine.poll(16500), "Home returns from the same activity without a new UsageEvent");
        check(t.shown == 1 && !t.engine.isTripped(), "One Home cue uses one cover and circuit request");
        t.target(16600);
        check(t.ready == 1 && t.engine.lastResult().startsWith("Observed"),
                "Settings return confirms and releases its cover on target observation");

        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 1000);
        t.engine.homePressed("homekey", 1100); t.home(1200);
        check(t.shown == 1 && t.cancelled == 0, "Duplicate broadcast and Home arrival coalesce into one cover");
        settingsReturn(t.engine.poll(6500), "Duplicates do not postpone the first Home deadline");
        check(t.engine.poll(6501) == null && !t.engine.isTripped(), "Duplicates do not add submissions");

        t = new Trace(); t.settings();
        t.foreground(RedirectEngine.SETTINGS_PACKAGE, "DeviceActivity", 1000);
        t.engine.homePressed("homekey", 2000);
        t.background(RedirectEngine.SETTINGS_PACKAGE, "DeviceActivity", 2100);
        t.home(2200);
        settingsReturn(t.engine.poll(7500), "Home from a Settings subpage survives its background/Home handover");
        check(t.shown == 1 && t.cancelled == 0, "Subpage handover does not stack or remove the Home cover");

        t = new Trace(); t.settings();
        t.foreground(RedirectEngine.SETTINGS_PACKAGE, "DeviceActivity", 1000);
        t.home(2000);
        check(t.engine.poll(10000) == null && t.engine.isBypassing() && t.shown == 0,
                "Back from a subpage keeps Settings accessible");
        t.engine.homePressed("recentapps", 11000);
        t.engine.homePressed("globalactions", 11001);
        t.engine.homePressed(null, 11002);
        t.engine.homePressed("homeKey", 11003);
        check(t.engine.poll(20000) == null && t.engine.isBypassing(),
                "Only the exact homekey reason can end the visit");

        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 1000);
        t.foreground("video.app", "Player", 1100);
        check(t.engine.poll(6500) == null && t.cancelled == 1,
                "Opening another app cancels the unsubmitted Settings return and cover");
        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 1000);
        t.background(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, 1100);
        check(t.engine.poll(6500) == null && t.cancelled == 1,
                "A missing Home arrival cannot launch from an unknown foreground at the deadline");
        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 1000);
        t.entry(1200); t.home(1300);
        check(t.engine.isBypassing() && t.engine.poll(7000) == null && t.cancelled == 1,
                "A newer explicit Settings request cancels an earlier Home return");

        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 1000);
        t.foreground(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.HUD_CLASS, 1100);
        RedirectEngine.Request request = t.engine.poll(6600);
        check(request != null && "hud_return".equals(request.reason) && t.cancelled == 1,
                "The exact HUD retains the existing long-Home return path");
    }

    private static void orderingAndGuardTests() {
        Trace t = new Trace(); t.target(100);
        t.engine.homePressed("homekey", 1000);
        t.engine.observe(TARGET, TARGET + ".MainActivity", false, true, 1100, 200);
        t.engine.observe(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.SETTINGS_ENTRY_CLASS,
                true, true, 1200, 500);
        t.engine.observe(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.SETTINGS_ENTRY_CLASS,
                false, true, 1250, 600);
        t.engine.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, true, 1300, 1005);
        settingsReturn(t.engine.poll(6500), "A Settings handler published after its newer Home cue cannot latch bypass");

        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 1000);
        t.engine.observe(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.SETTINGS_ENTRY_CLASS,
                true, true, 1200, 900);
        t.engine.observe(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.SETTINGS_ENTRY_CLASS,
                false, true, 1250, 950);
        t.engine.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, true, 1300, 1005);
        check(t.shown == 1 && t.cancelled == 0,
                "An earlier Settings handler delivered late cannot cancel an existing Home cover");
        settingsReturn(t.engine.poll(6500), "Late Settings publication preserves the original Home deadline");

        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 140);
        check(t.engine.isBypassing() && t.engine.poll(10000) == null,
                "A delayed Home cue older than the Settings entry cannot end the new visit");
        t = new Trace(); t.engine.homePressed("homekey", 1000);
        t.engine.resetObservation("clock or cursor reset"); t.entry(1100); t.home(1200);
        check(t.engine.isBypassing() && t.engine.poll(10000) == null, "Observation reset forgets old Home cues");

        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 1000);
        t.engine.permission(false);
        check(t.engine.poll(6500) == null && t.cancelled == 1, "Permission loss cancels the Home cue request");
        t.engine.permission(true); t.entry(1100); t.home(1200);
        check(t.engine.isBypassing() && t.engine.poll(10000) == null, "Permission restoration does not replay an old cue");
        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 1000);
        t.engine.screen(false, false, 1100); t.engine.screen(true, false, 2000);
        t.entry(2100); t.home(2200);
        check(t.engine.isBypassing() && t.engine.poll(10000) == null && t.cancelled == 1,
                "Standby ends the old cue without replay in the new Settings visit");
        t = new Trace(); t.settings(); t.engine.homePressed("homekey", 1000);
        t.engine.configure(TARGET, true, true, false, false, false);
        t.entry(1100); t.home(1200);
        check(t.engine.isBypassing() && t.engine.poll(10000) == null, "Configuration reset forgets the old cue");

        for (int guard = 0; guard < 5; guard++) {
            t = new Trace();
            t.engine.configure(guard == 4 ? "" : TARGET, guard != 0, true, false,
                    guard == 1, guard == 2);
            t.settings();
            if (guard == 3) t.engine.permission(false);
            t.engine.homePressed("homekey", 1000);
            check(t.engine.poll(6500) == null && t.shown == 0,
                    "Home signal respects disabled/diagnostic/suspended/permission/target guard " + guard);
        }
        t = new Trace(); t.settings(); t.engine.screen(false, false, 500);
        t.engine.homePressed("homekey", 1000);
        check(t.engine.poll(6500) == null && t.shown == 0, "A Home cue in standby cannot launch");

        t = new Trace(); t.engine.manualAmazon(); t.entry(100); t.home(200);
        t.engine.homePressed("homekey", 1000);
        check(t.engine.isBypassing() && t.engine.poll(10000) == null,
                "Settings and Home cues do not convert an intentional Amazon escape into a Settings visit");
        t.foreground(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.HUD_CLASS, 11000);
        RedirectEngine.Request request = t.engine.poll(16500);
        check(request != null && "hud_return".equals(request.reason), "Long-Home still returns from intentional Amazon escape");

        t = new Trace();
        t.engine.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, false, 100);
        t.engine.homePressed("homekey", 1000);
        check(t.engine.poll(10000) == null, "A cue without a preserved Settings visit does not add a general Home launch path");
    }

    public static void main(String[] args) {
        bootTests();
        settingsTests();
        orderingAndGuardTests();
        System.out.println("Startup/Home signal: " + checks + " behavioral assertions passed");
    }
}
