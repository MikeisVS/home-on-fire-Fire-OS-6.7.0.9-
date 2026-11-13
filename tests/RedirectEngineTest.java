// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.toolicious.homeonfire;

import java.util.ArrayList;
import java.util.List;

/** Executable behavioral tests without Android mocks or external dependencies. */
public final class RedirectEngineTest {
    private static final String TARGET = "com.conreo.couchytv";
    private static final String TARGET_CLASS = TARGET + ".MainActivity";
    private static final String SETTINGS_ENTRY =
            "com.amazon.tv.settings.v2.compliance.CTSDummySettingsHandler";
    private static int checks;
    private static final List<String> logs = new ArrayList<String>();

    private static RedirectEngine engine(boolean diagnostic) {
        RedirectEngine engine = new RedirectEngine((event, pkg, cls, detail) -> logs.add(event));
        engine.configure(TARGET, true, true, true, diagnostic, false);
        engine.permission(true);
        engine.screen(true, false, 0);
        return engine;
    }
    private static void check(boolean test, String label) {
        checks++;
        if (!test) throw new AssertionError(label);
    }
    private static void home(RedirectEngine e, long now) {
        e.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, true, now);
    }
    private static void target(RedirectEngine e, long now) {
        e.observe(TARGET, TARGET_CLASS, true, true, now);
    }
    private static void hud(RedirectEngine e, long now) {
        e.observe(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.HUD_CLASS, true, true, now);
    }
    private static void settingsRequest(RedirectEngine e, long now) {
        e.observe(RedirectEngine.SETTINGS_PACKAGE, SETTINGS_ENTRY, true, true, now);
    }

    public static void main(String[] args) {
        RedirectEngine e = engine(false);
        e.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, false, 0);
        check(e.poll(100000) == null, "Seeding home must not launch");
        target(e, 100100); home(e, 100200);
        check(e.poll(105699) == null, "Do not submit inside the 5.5-second local delay");
        RedirectEngine.Request r = e.poll(105700);
        check(r != null && TARGET.equals(r.packageName), "Home submits target once after delay");
        check(e.poll(105701) == null, "Only one outstanding request");
        target(e, 105800);
        check(e.lastResult().startsWith("Observed"), "Submission is confirmed only by foreground");
        e = engine(false); home(e, 100); e.poll(5600);
        e.observe(TARGET, TARGET_CLASS, true, true, 5700, 5000);
        check(!e.lastResult().startsWith("Observed"), "Late pre-submission event cannot confirm a launch");
        e.observe(TARGET, TARGET_CLASS, true, true, 6100, 6000);
        check(e.lastResult().startsWith("Observed"), "Post-submission event confirms a launch");

        e = engine(false); home(e, 100);
        e.observe("video.app", "video.app.Player", true, true, 200);
        check(e.poll(6000) == null, "Navigation away cancels pending Home");
        e = engine(false); home(e, 100);
        e.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, false, true, 300);
        check(e.poll(6000) == null, "Background event cancels even without a successor foreground");
        e = engine(false); home(e, 100); home(e, 5000);
        check(e.poll(5600) != null, "Duplicate Home foreground must not postpone the deadline");
        e = engine(false);
        e.observe(RedirectEngine.HOME_PACKAGE, "SomeOtherActivity", true, true, 100);
        check(e.poll(6000) == null, "Package alone must not match Home");

        e = engine(false); target(e, 100); hud(e, 200);
        r = e.poll(200);
        check(r != null && r.amazon, "HUD from target escapes to Amazon");
        home(e, 250);
        check(e.isBypassing(), "Escape latches Amazon visit");
        e.observe(RedirectEngine.SETTINGS_PACKAGE, "SettingsActivity", true, true, 300);
        home(e, 400);
        check(e.poll(6000) == null, "Visiting Settings does not end Amazon escape");
        hud(e, 7000);
        check(e.poll(12499) == null, "HUD return also respects local delay");
        r = e.poll(12500);
        check(r != null && TARGET.equals(r.packageName), "HUD over Amazon returns to target");
        target(e, 12600);
        check(!e.isBypassing(), "Target foreground ends bypass");
        e = engine(false);
        e.observe("video.app", "Player", true, true, 1); hud(e, 2);
        check(e.poll(8000) == null, "HUD over third-party apps is left alone");
        e = engine(false); target(e, 1);
        e.observe(RedirectEngine.SETTINGS_PACKAGE, "PreferencesActivity", true, true, 2);
        check(e.poll(8000) == null, "Ordinary Settings is not a long press");

        // On this Cube ACTION_SETTINGS is a short-lived handler followed by the
        // same Amazon activity used for Home. Reproduce the actual event sequence.
        final List<String> settingsCovers = new ArrayList<String>();
        e = new RedirectEngine((event, pkg, cls, detail) -> logs.add(event),
                new RedirectEngine.CoverListener() {
                    public void show(long id, String target) { settingsCovers.add("show"); }
                    public void targetObserved(long id) { settingsCovers.add("observed"); }
                    public void cancel(long id, String why) { settingsCovers.add("cancel"); }
                });
        e.configure(TARGET, true, true, true, false, false);
        e.permission(true); e.screen(true, false, 0);
        target(e, 100);
        e.observe(TARGET, TARGET_CLASS, false, true, 200);
        settingsRequest(e, 250);
        e.observe(RedirectEngine.SETTINGS_PACKAGE, SETTINGS_ENTRY, false, true, 300);
        home(e, 320);
        check(e.isBypassing(), "Couchy Android Settings preserves the Amazon settings visit");
        check(e.poll(6000) == null, "Settings handler to Amazon is not redirected to Couchy");
        check(settingsCovers.isEmpty(), "Explicit Settings must never show the Home loading cover");
        check(!e.isTripped(), "Opening Settings consumes no automatic request or circuit slot");
        home(e, 6500);
        check(e.poll(12500) == null, "Duplicate Amazon events keep Settings accessible");
        e.observe(RedirectEngine.SETTINGS_PACKAGE, "DeviceActivity", true, true, 13000);
        home(e, 14000);
        check(e.poll(20000) == null, "Settings navigation back to its root keeps the visit accessible");
        hud(e, 21000);
        check(e.poll(26499) == null, "Long-Home return from Settings retains its normal delay");
        r = e.poll(26500);
        check(r != null && "hud_return".equals(r.reason), "Long-Home still returns from Settings to Couchy");
        target(e, 26600);
        check(!e.isBypassing(), "Returning to Couchy clears the Settings visit");
        home(e, 30000);
        check(e.poll(35500) != null, "Normal Home redirects again after leaving Settings");

        e = engine(false); home(e, 100); settingsRequest(e, 200); home(e, 300);
        check(e.poll(6000) == null, "Explicit Settings cancels an unsubmitted Home redirect");
        e = engine(false); e.boot(100); settingsRequest(e, 200); home(e, 300);
        check(e.poll(6000) == null, "Explicit Settings cancels an unsubmitted boot redirect");
        e = engine(false); e.screen(false, false, 0); e.screen(true, true, 100);
        settingsRequest(e, 200); home(e, 300);
        check(e.poll(6000) == null, "Explicit Settings cancels an unsubmitted wake redirect");
        e = engine(false); settingsRequest(e, 100);
        e.observe("video.app", "Player", true, true, 200); home(e, 300);
        check(e.poll(5800) != null, "Opening another app ends the preserved Settings visit");
        e = engine(false);
        e.observe(RedirectEngine.SETTINGS_PACKAGE, SETTINGS_ENTRY, true, false, 100);
        home(e, 200);
        check(!e.isBypassing() && e.poll(5700) != null, "Historical Settings events cannot suppress a later Home");
        e = engine(false);
        e.observe("other.package", SETTINGS_ENTRY, true, true, 100); home(e, 200);
        check(e.poll(5700) != null, "A matching class in another package is not a Settings request");
        e = engine(false);
        e.observe(RedirectEngine.SETTINGS_PACKAGE,
                "com.amazon.tv.settings.v2.compliance.CTSDummyIntentHandler", true, true, 100);
        home(e, 200);
        check(e.poll(5700) != null, "Other compliance handlers do not bypass Home redirection");
        e = engine(false); e.permission(false); settingsRequest(e, 100);
        check(!e.isBypassing(), "Unavailable observation permission cannot infer a Settings request");
        e = engine(false); e.screen(false, false, 0); settingsRequest(e, 100);
        check(!e.isBypassing(), "Standby observations cannot infer a Settings request");
        e = engine(false); settingsRequest(e, 100); e.screen(false, false, 200);
        e.screen(true, true, 300);
        check(!e.isBypassing(), "A new wake session ends the preserved Settings visit");
        check(e.poll(5800) != null, "Preserving Settings does not disable the next wake launch");
        check(logs.contains("settings_visit_preserved"), "Settings bypass has an explicit diagnostic event");

        e = engine(false);
        e.configure(TARGET, true, true, true, false, false, false);
        target(e, 1); hud(e, 2);
        check(e.poll(8000) == null, "Disabled long-press leaves the HUD over target alone");
        check(!e.isBypassing(), "Disabled long-press does not latch an Amazon visit");
        e = engine(false);
        e.configure(TARGET, true, true, true, false, false, false);
        e.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, false, 1);
        hud(e, 2);
        check(e.poll(8000) == null, "Disabled long-press leaves the HUD over Amazon alone");
        e = engine(false);
        e.configure(TARGET, true, true, true, false, false, false);
        target(e, 1); home(e, 2);
        check(e.poll(5501) == null, "Disabling long-press does not shorten the Home delay");
        r = e.poll(5502);
        check(r != null && TARGET.equals(r.packageName), "Normal Home works with long-press disabled");
        e = engine(false);
        e.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, false, 1);
        hud(e, 2);
        check("hud_return".equals(e.pendingReason()), "HUD return is pending before configuration change");
        e.configure(TARGET, true, true, true, false, false, false);
        check(e.poll(8000) == null, "Turning long-press off cancels a pending HUD return");
        e.configure(TARGET, true, true, true, false, false, true);
        e.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, false, 9000);
        e.observe(RedirectEngine.SETTINGS_PACKAGE, RedirectEngine.HUD_CLASS, true, false, 9001);
        check(e.poll(15000) == null, "Restoring long-press does not replay historical HUD observations");
        home(e, 16000); hud(e, 16001);
        r = e.poll(21501);
        check(r != null && TARGET.equals(r.packageName), "Restored long-press handles a fresh HUD return");
        e = engine(false);
        e.configure(TARGET, true, true, true, false, false, false);
        e.boot(100);
        r = e.poll(5600);
        check(r != null && "boot".equals(r.reason), "Boot remains enabled with long-press disabled");
        e = engine(false);
        e.configure(TARGET, true, true, true, false, false, false);
        e.screen(false, false, 100); e.screen(true, true, 200);
        r = e.poll(5700);
        check(r != null && "wake".equals(r.reason), "Wake remains enabled with long-press disabled");

        e = engine(true); home(e, 100); e.boot(200);
        e.screen(false, false, 300); e.screen(true, true, 400);
        check(e.poll(10000) == null, "Diagnostic mode must never launch from Home, boot, or wake");
        e = engine(false); home(e, 100); e.screen(false, false, 200);
        check(e.poll(6000) == null, "Standby cancels pending launch");
        e.screen(true, true, 7000);
        e.observe("video.app", "Player", true, false, 7000);
        r = e.poll(12500);
        check(r != null && r.reason.equals("wake"), "Wake overrides previously foreground video app");
        e.screen(true, true, 12600);
        check(e.poll(12600) == null, "Duplicate wake signals are coalesced");
        target(e, 12700);
        e = engine(false); e.screen(false, false, 0); e.boot(100);
        check(e.poll(20000) == null, "Boot waits while non-interactive");
        e.screen(true, true, 21000);
        r = e.poll(26500);
        check(r != null && r.reason.equals("boot"), "Boot and wake coalesce into one request");
        e = engine(false);
        e.configure(TARGET, true, false, true, false, false);
        e.screen(false, false, 0); e.boot(100); e.screen(true, true, 200);
        r = e.poll(5700);
        check(r != null && r.reason.equals("wake"), "Disabled boot must not suppress enabled wake");
        e = engine(false); e.boot(100); e.screen(false, false, 200);
        e.screen(true, true, 1000);
        r = e.poll(6500);
        check(r != null && r.reason.equals("boot"), "Boot request survives standby before submission");

        e = engine(false); home(e, 100); e.permission(false);
        check(e.poll(6000) == null, "Permission revocation cancels Home");
        e.boot(7000);
        check(e.poll(12500) != null, "Boot fallback does not require Usage Access");
        e.poll(22500);
        check(!e.isTripped(), "Lack of observation permission is not proof a boot launch failed");
        e = engine(false); home(e, 100);
        e.configure(TARGET, true, true, true, true, false);
        check(e.poll(6000) == null, "Configuration change cancels pending launch");
        e = engine(false); home(e, 100); e.poll(5600);
        e.failed("ActivityNotFoundException");
        check(e.isTripped(), "Launch exceptions suspend instead of retrying");
        e = engine(false); home(e, 100); e.poll(5600); e.poll(15600);
        check(!e.isTripped(), "Missing foreground confirmation alone must not suspend automation");
        check(e.lastResult().startsWith("Launch unconfirmed"), "Timeout remains visibly unconfirmed");
        check(e.poll(15601) == null, "Unconfirmed launch is not automatically retried");
        target(e, 15700); home(e, 15800);
        check(e.poll(21300) != null, "A fresh Home transition still works after an unconfirmed launch");
        e.suspend("explicit suspension test");
        e.resume();
        check(!e.isTripped(), "Explicit resume clears suspension");
        e.manualAmazon(); e.resume();
        check(!e.isBypassing(), "Explicit resume ends a preserved Amazon visit");

        e = engine(false); e.screen(false, false, 100); e.screen(true, true, 200);
        check(e.poll(5700) != null, "Wake can request a target with unknown foreground state");
        check(e.poll(15700) == null && !e.isTripped(),
                "Wake without a new target event stays unconfirmed without retry or suspension");
        e.screen(false, false, 16000); e.screen(true, true, 16200);
        check(e.poll(21700) != null, "A subsequent distinct wake remains automatic");

        e = engine(false);
        for (int i = 0; i < 3; i++) {
            long start = 100 + i * 6500;
            target(e, start); home(e, start + 100);
            check(e.poll(start + 5600) != null, "Automatic request " + (i + 1) + " accepted");
            target(e, start + 5700);
        }
        check(e.isTripped(), "Third request in 30 seconds opens circuit");
        home(e, 21000);
        check(e.poll(27000) == null, "Fourth automatic request is blocked");
        check(logs.contains("home_inferred") && logs.contains("launch_observed")
                && logs.contains("launch_cancelled") && logs.contains("circuit_open"),
                "Policy emits actionable diagnostic outcomes");
        System.out.println("RedirectEngine: " + checks + " behavioral assertions passed");
    }
}
