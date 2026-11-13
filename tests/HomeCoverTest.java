// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.toolicious.homeonfire;

import java.util.ArrayList;
import java.util.List;

/** Cover timing and real engine signals; no Android mocks or device changes. */
public final class HomeCoverTest {
    private static final String TARGET = "com.conreo.couchytv";
    private static int checks;

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static final class Signals implements RedirectEngine.CoverListener {
        final List<String> actions = new ArrayList<String>();
        long shownId;
        String shownTarget;
        @Override public void show(long id, String target) {
            shownId = id;
            shownTarget = target;
            actions.add("show:" + id);
        }
        @Override public void targetObserved(long id) { actions.add("ready:" + id); }
        @Override public void cancel(long id, String reason) { actions.add("cancel:" + id); }
        int count(String action) {
            int result = 0;
            for (String entry : actions) if (entry.startsWith(action + ":")) result++;
            return result;
        }
    }

    private static RedirectEngine engine(Signals cover, boolean diagnostic) {
        RedirectEngine engine = new RedirectEngine((event, pkg, cls, detail) -> {}, cover);
        engine.configure(TARGET, true, true, true, diagnostic, false);
        engine.permission(true);
        engine.screen(true, false, 0);
        return engine;
    }

    private static void home(RedirectEngine engine, long now) {
        engine.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, true, now);
    }

    private static void target(RedirectEngine engine, long now) {
        engine.observe(TARGET, TARGET + ".MainActivity", true, true, now);
    }

    private static void stateTests() {
        HomeCoverState state = new HomeCoverState();
        check(state.start(1, 100), "Fresh request can show immediately");
        check(state.phase() == HomeCoverState.Phase.WAITING, "Cover starts fully opaque");
        check(state.watchdogDeadline() == 10100, "Watchdog is ten seconds from show");
        check(!state.start(1, 5000), "Duplicate show is ignored");
        check(state.watchdogDeadline() == 10100, "Duplicate show cannot renew watchdog");
        check(!state.targetObserved(2, 6000), "Wrong request cannot finish a cover");
        check(state.phase() == HomeCoverState.Phase.WAITING, "Wrong observation leaves wait intact");
        check(state.targetObserved(1, 6000), "Matching target begins opaque hold");
        check(!state.targetObserved(1, 6600), "Duplicate confirmation cannot renew hold");
        check(state.advance(6699) == HomeCoverState.Action.NONE, "Hold lasts the full 700 ms");
        check(state.advance(6700) == HomeCoverState.Action.FADE, "Fade starts at hold boundary");
        check(state.fadeDuration(6700) == 100, "Normal fade lasts 100 ms");
        check(state.advance(6799) == HomeCoverState.Action.NONE, "Fade stays attached through its span");
        check(state.advance(6800) == HomeCoverState.Action.REMOVE, "Fade has a removal backstop");
        check(state.id() == 0, "Completed cover is retired");
        check(!state.start(1, 9000), "Late show cannot resurrect completed request");

        state = new HomeCoverState();
        check(!state.cancel(4), "Cancellation before show needs no window");
        check(!state.start(4, 100), "Cancelled queued show is rejected");
        check(!state.start(3, 100), "Older queued show is rejected");
        check(state.start(5, 100), "A later independent request remains available");
        check(!state.cancel(4), "Stale removal does not clear the newer window");
        check(state.id() == 5, "Newer request survives an old fade callback");
        check(!state.targetObserved(4, 200), "Stale confirmation does not start a new fade");
        check(state.cancel(5), "Matching cancellation clears immediately");
        check(state.phase() == HomeCoverState.Phase.IDLE, "Cancelled cover has no remaining hold");

        state = new HomeCoverState();
        state.start(1, 100);
        check(state.advance(10099) == HomeCoverState.Action.NONE, "Missing confirmation stays covered before watchdog");
        check(state.advance(10100) == HomeCoverState.Action.REMOVE, "Missing confirmation removes at watchdog");
        check(!state.targetObserved(1, 11000), "Late confirmation cannot resurrect watchdog-cleared cover");
        check(!state.start(1, 12000), "Watchdog-cleared request cannot show again");
        check(state.start(2, 12000), "A watchdog does not disable future Home covers");

        state = new HomeCoverState();
        state.start(1, 0);
        check(state.targetObserved(1, 9800), "Near-deadline target is observed");
        check(state.advance(10000) == HomeCoverState.Action.REMOVE, "Watchdog caps even an unfinished opaque hold");
        state.start(2, 20000);
        state.targetObserved(2, 29250);
        check(state.advance(29950) == HomeCoverState.Action.FADE, "Late fade can start before hard cap");
        check(state.fadeDuration(29950) == 50, "Fade is shortened to respect hard deadline");
        check(state.advance(30000) == HomeCoverState.Action.REMOVE, "Shortened fade still removes at deadline");

        state = new HomeCoverState();
        state.start(1, 100);
        state.targetObserved(1, 500);
        check(state.advance(2000) == HomeCoverState.Action.REMOVE, "Late main callback removes instead of starting an overdue fade");
        state.start(2, 3000);
        state.cancel(2); // The renderer reports permission/attachment failure.
        check(!state.start(2, 3100), "Attachment failure is not retried for the same Home visit");
        check(state.start(3, 4000), "Attachment failure does not block later requests");
        state.dismiss();
        check(state.id() == 0 && !state.start(3, 5000), "Configuration removal retires the visible request");
        state.close();
        check(!state.start(4, 6000), "Service destruction rejects all queued future shows");
        check(state.advance(50000) == HomeCoverState.Action.NONE, "Destroyed cover has no remaining timer action");
    }

    private static void signalTests() {
        Signals cover = new Signals();
        RedirectEngine engine = engine(cover, false);
        home(engine, 100);
        long id = cover.shownId;
        check(id > 0 && TARGET.equals(cover.shownTarget), "Accepted Home emits request identity and selected target");
        check(cover.count("show") == 1, "Home emits one immediate show before submission");
        home(engine, 3000);
        check(cover.count("show") == 1, "Duplicate Home emits no additional show");
        check(engine.poll(5599) == null, "Cover does not accelerate the local launch delay");
        RedirectEngine.Request request = engine.poll(5600);
        check(request != null && request.id == id, "Submission retains the covered request identity");
        check(cover.count("ready") == 0, "Submitting a start does not confirm the cover");
        engine.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, false, true, 5650);
        check(cover.count("cancel") == 0, "Expected Amazon background handover stays covered");
        engine.observe(TARGET, TARGET + ".MainActivity", true, true, 5700, 5000);
        check(cover.count("ready") == 0, "Delayed pre-submission target event cannot lift cover");
        target(engine, 5800);
        check(cover.count("ready") == 1 && cover.actions.contains("ready:" + id), "Fresh target observation releases only its cover");
        target(engine, 5900);
        check(cover.count("ready") == 1, "Repeated target observation does not reset hold");
        engine.observe(TARGET, TARGET + ".MainActivity", false, true, 6000);
        check(cover.count("cancel") == 1, "Target leaving during hold clears cover immediately");
        home(engine, 6100);
        check(cover.shownId > id, "Later Home obtains a newer presentation identity");

        cover = new Signals(); engine = engine(cover, false); home(engine, 100);
        engine.observe("video.app", "Player", true, true, 200);
        check(cover.count("cancel") == 1 && engine.poll(6000) == null, "Navigation before submission cancels cover and local launch");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100);
        engine.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, false, true, 200);
        check(cover.count("cancel") == 1, "Background before submission clears pending cover");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.poll(5600);
        engine.observe("video.app", "Player", true, true, 5700);
        check(cover.count("cancel") == 1, "Navigation after submission clears only presentation");
        check(engine.poll(6000) == null, "Presentation cancellation does not submit another Android start");
        target(engine, 6200);
        check(engine.lastResult().startsWith("Observed") && cover.count("ready") == 0,
                "Already-submitted start can still confirm without resurrecting its cancelled cover");

        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.permission(false);
        check(cover.count("cancel") == 1, "Usage permission loss clears pending cover");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.poll(5600); engine.permission(false);
        check(cover.count("cancel") == 1, "Usage permission loss clears a submitted cover too");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.screen(false, false, 200);
        check(cover.count("cancel") == 1, "Standby clears cover");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.resetObservation("clock changed");
        check(cover.count("cancel") == 1, "Observation reset clears cover");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.poll(5600);
        engine.configure(TARGET, true, true, true, false, false);
        check(cover.count("cancel") == 1, "Configuration clears even a submitted cover");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.resume();
        check(cover.count("cancel") == 1, "Explicit resume clears presentation without replay");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.poll(5600); engine.manualAmazon();
        check(cover.count("cancel") == 1 && engine.isBypassing(), "Manual Amazon access clears cover and preserves bypass");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.poll(5600); engine.failed("Missing target");
        check(cover.count("cancel") == 1 && engine.isTripped(), "Launch failure clears cover and keeps existing suspension");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.poll(5600); engine.poll(15600);
        check(cover.count("cancel") == 1 && !engine.isTripped(), "Unconfirmed start clears cover without changing timeout policy");
        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.suspend("query failure");
        check(cover.count("cancel") == 1, "Explicit query/safety suspension clears cover");

        cover = new Signals(); engine = engine(cover, true); home(engine, 100);
        check(cover.count("show") == 0, "Diagnostic-only observation never displays a cover");
        cover = new Signals(); engine = engine(cover, false);
        engine.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, false, 100);
        check(cover.count("show") == 0, "Historical Home seeding never displays a cover");
        cover = new Signals(); engine = engine(cover, false);
        engine.configure(TARGET, false, true, true, false, false); engine.permission(true); home(engine, 100);
        check(cover.count("show") == 0, "Home disabled never displays a cover");
        cover = new Signals(); engine = engine(cover, false);
        engine.configure(TARGET, true, true, true, false, true); engine.permission(true); home(engine, 100);
        check(cover.count("show") == 0, "Persisted suspension never displays a cover");
        cover = new Signals(); engine = engine(cover, false); engine.boot(100); home(engine, 200);
        check(cover.count("show") == 0, "Home coalesced into boot does not cover the boot launch");
        cover = new Signals(); engine = engine(cover, false); engine.screen(false, false, 100); engine.screen(true, true, 200); home(engine, 300);
        check(cover.count("show") == 0, "Home coalesced into wake does not cover the wake launch");
        cover = new Signals(); engine = engine(cover, false); target(engine, 100);
        engine.observe(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.HUD_CLASS, true, true, 200);
        request = engine.poll(200);
        check(request != null && request.amazon && cover.count("show") == 0, "HUD escape remains uncovered and immediate");
        home(engine, 300);
        engine.observe(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.HUD_CLASS, true, true, 7000);
        check(cover.count("show") == 0 && engine.poll(12500) != null, "HUD return retains its delay without a Home cover");

        cover = new Signals(); engine = engine(cover, false); home(engine, 100); engine.boot(200);
        check(cover.count("cancel") == 1 && cover.count("show") == 1, "Replacing pending Home with boot clears only the Home cover");
        cover = new Signals(); engine = engine(cover, false);
        for (int i = 0; i < 3; i++) {
            long start = 100 + i * 6500;
            target(engine, start); home(engine, start + 100); engine.poll(start + 5600); target(engine, start + 5700);
        }
        check(engine.isTripped(), "Cover does not change the three-request circuit");
        check(cover.count("show") == 3 && cover.count("ready") == 2,
                "Third request suspension clears its cover before any later confirmation");
    }

    public static void main(String[] args) {
        stateTests();
        signalTests();
        System.out.println("Home cover: " + checks + " behavioral assertions passed");
    }
}
