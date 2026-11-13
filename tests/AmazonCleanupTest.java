// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.toolicious.homeonfire;

import java.util.ArrayList;
import java.util.List;

/** Behavioral checks for cleanup timing, cancellations and a void/failed Android adapter. */
public final class AmazonCleanupTest {
    private static final String COUCHY = AmazonCleanupPolicy.COUCHY_PACKAGE;
    private static final String MAIN = COUCHY + ".MainActivity";
    private static int checks;

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static final class Fake implements AmazonCleanupPolicy.Cleaner, AmazonCleanupPolicy.Log {
        int calls;
        RuntimeException failure;
        final List<String> events = new ArrayList<String>();
        @Override public void killAmazonBackground() {
            calls++;
            if (failure != null) throw failure;
        }
        @Override public void event(String event, String detail) { events.add(event + ":" + detail); }
        int count(String event) {
            int result = 0;
            for (String item : events) if (item.startsWith(event + ":")) result++;
            return result;
        }
    }

    private static AmazonCleanupPolicy ready(Fake fake) {
        AmazonCleanupPolicy policy = new AmazonCleanupPolicy(fake, fake);
        policy.configure(true, COUCHY);
        policy.eligibility(true, "eligible");
        return policy;
    }

    private static void couchy(AmazonCleanupPolicy policy, long now) {
        policy.observe(COUCHY, MAIN, true, true, now);
    }

    private static void away(AmazonCleanupPolicy policy, long now) {
        policy.observe("com.esaba.downloader", "Downloader", true, true, now);
    }

    private static void timingAndVisits() {
        Fake fake = new Fake();
        AmazonCleanupPolicy policy = ready(fake);
        couchy(policy, 100);
        check(fake.calls == 0, "A foreground event never dispatches cleanup inside its batch");
        check(policy.poll(1099) == AmazonCleanupPolicy.Result.NONE, "Full one-second hold is required");
        couchy(policy, 800);
        check(policy.poll(1100) == AmazonCleanupPolicy.Result.REQUESTED, "Duplicate foreground does not extend hold");
        check(fake.calls == 1, "One adapter call for the first Couchy visit");
        check(policy.status().contains("unverified"), "Void API never claims RAM savings or process exit");
        check(fake.count("cleanup_attempt") == 1 && fake.count("cleanup_requested") == 1,
                "Requested and attempted diagnostics are distinct");
        couchy(policy, 2000);
        policy.observe(COUCHY, COUCHY + ".SettingsActivity", true, true, 2100);
        policy.observe(AmazonCleanupPolicy.AMAZON_PACKAGE, "Home", false, true, 2200);
        check(policy.poll(5000) == AmazonCleanupPolicy.Result.NONE && fake.calls == 1,
                "Duplicates, in-package screens and Amazon background activity cannot repeat cleanup");
        away(policy, 6000);
        couchy(policy, 6100);
        check(policy.poll(7100) == AmazonCleanupPolicy.Result.REQUESTED && fake.calls == 2,
                "A real return to Couchy permits the next attempt");
        check(policy.poll(20000) == AmazonCleanupPolicy.Result.NONE, "Long Couchy dwell does not retry");
    }

    private static void completeBatchAndBackground() {
        Fake fake = new Fake();
        AmazonCleanupPolicy policy = ready(fake);
        couchy(policy, 100);
        away(policy, 100);
        check(policy.poll(2000) == AmazonCleanupPolicy.Result.NONE && fake.calls == 0,
                "A later event in the same batch cancels the earlier Couchy arrival");
        check(fake.count("cleanup_cancelled") == 1, "Navigation cancellation is recorded once");
        couchy(policy, 3000);
        policy.observe(COUCHY, "UnrelatedActivity", false, true, 3100);
        check(policy.poll(4000) == AmazonCleanupPolicy.Result.REQUESTED,
                "A background event for another activity cannot clear current Couchy");
        away(policy, 5000);
        couchy(policy, 5100);
        policy.observe(COUCHY, MAIN, false, true, 5200);
        check(policy.poll(6200) == AmazonCleanupPolicy.Result.NONE, "Current Couchy background cancels hold");
        couchy(policy, 6300);
        check(policy.poll(7300) == AmazonCleanupPolicy.Result.REQUESTED,
                "A fresh visit after background starts a new hold");
    }

    private static void historyAndConfiguration() {
        Fake fake = new Fake();
        AmazonCleanupPolicy policy = ready(fake);
        policy.observe(COUCHY, MAIN, true, false, 100);
        check(policy.poll(2000) == AmazonCleanupPolicy.Result.NONE, "Seeded Couchy never triggers cleanup");
        couchy(policy, 3000);
        check(policy.poll(4000) == AmazonCleanupPolicy.Result.NONE,
                "A duplicate of the seeded foreground is not a new arrival");
        away(policy, 5000);
        couchy(policy, 5100);
        policy.observe(COUCHY, MAIN, true, false, 5200);
        check(policy.poll(6500) == AmazonCleanupPolicy.Result.NONE, "Stale history cancels a pending request");
        policy.configure(false, COUCHY);
        policy.eligibility(true, "enabled monitor");
        couchy(policy, 7000);
        check(policy.poll(8000) == AmazonCleanupPolicy.Result.NONE, "Default-off option dispatches nothing");
        policy.configure(true, "another.launcher");
        policy.eligibility(true, "enabled monitor");
        couchy(policy, 9000);
        check(policy.poll(10000) == AmazonCleanupPolicy.Result.NONE, "Only selected Couchy is supported");
        check(policy.status().contains("select Couchy"), "Unsupported target is visible");
        policy.configure(true, COUCHY);
        policy.eligibility(true, "eligible");
        away(policy, 11000);
        couchy(policy, 11100);
        policy.configure(false, COUCHY);
        check(policy.poll(13000) == AmazonCleanupPolicy.Result.NONE, "Turning cleanup off cancels pending work");
        check(fake.calls == 0, "History and configuration scenarios never invoke Android");
    }

    private static void gatesAndResets() {
        for (String reason : new String[] { "Usage Access unavailable", "diagnostic only",
                "automation suspended", "monitor stopped", "competing controller", "Amazon visit preserved" }) {
            Fake fake = new Fake();
            AmazonCleanupPolicy policy = ready(fake);
            couchy(policy, 100);
            policy.eligibility(false, reason);
            check(policy.poll(1200) == AmazonCleanupPolicy.Result.NONE && fake.calls == 0,
                    reason + " cancels pending cleanup");
            policy.eligibility(true, "restored");
            couchy(policy, 1300);
            check(policy.poll(2500) == AmazonCleanupPolicy.Result.NONE,
                    reason + " recovery cannot replay the current visit");
            away(policy, 2600);
            couchy(policy, 2700);
            check(policy.poll(3700) == AmazonCleanupPolicy.Result.REQUESTED,
                    reason + " recovery accepts a genuinely new visit");
        }
        for (String reason : new String[] { "standby", "wall clock changed", "event query failed",
                "service stopped", "foreground seeding", "manual Amazon launch" }) {
            Fake fake = new Fake();
            AmazonCleanupPolicy policy = ready(fake);
            couchy(policy, 100);
            policy.reset(reason);
            check(policy.poll(1200) == AmazonCleanupPolicy.Result.NONE && fake.calls == 0,
                    reason + " retires pending work");
            policy.observe(COUCHY, MAIN, true, false, 1300);
            check(policy.poll(2500) == AmazonCleanupPolicy.Result.NONE,
                    reason + " historical recovery remains read-only");
        }
        Fake fake = new Fake();
        AmazonCleanupPolicy policy = ready(fake);
        policy.observe(null, null, true, true, 0);
        check(policy.poll(2000) == AmazonCleanupPolicy.Result.NONE, "Unknown foreground cannot trigger cleanup");
    }

    private static void exceptionsAndRedirection() {
        Fake fake = new Fake();
        fake.failure = new SecurityException("permission denied");
        AmazonCleanupPolicy policy = ready(fake);
        RedirectEngine engine = new RedirectEngine((event, pkg, cls, detail) -> {});
        engine.configure(COUCHY, true, true, true, false, false);
        engine.permission(true);
        engine.screen(true, false, 0);
        couchy(policy, 100);
        check(policy.poll(1100) == AmazonCleanupPolicy.Result.FAILED, "Permission failure is explicit");
        check(policy.status().contains("failed and disabled"), "Failure is shown without a success claim");
        check(fake.count("cleanup_failed") == 1 && fake.count("cleanup_requested") == 0,
                "Failed adapter cannot record a returned request");
        away(policy, 2000);
        couchy(policy, 2100);
        policy.eligibility(true, "monitor still enabled");
        check(policy.poll(4000) == AmazonCleanupPolicy.Result.NONE && fake.calls == 1,
                "A failed cleanup remains disabled across future visits");
        engine.observe(RedirectEngine.HOME_PACKAGE, RedirectEngine.HOME_CLASS, true, true, 5000);
        check(engine.poll(10500) != null && !engine.isTripped(),
                "Cleanup failure leaves the normal Home engine available");
        fake.failure = null;
        policy.configure(true, COUCHY);
        policy.eligibility(true, "explicit re-enable");
        couchy(policy, 11000);
        check(policy.poll(12000) == AmazonCleanupPolicy.Result.REQUESTED,
                "Explicit re-enable starts a new verified arrival, not an implicit retry");
    }

    public static void main(String[] args) {
        timingAndVisits();
        completeBatchAndBackground();
        historyAndConfiguration();
        gatesAndResets();
        exceptionsAndRedirection();
        System.out.println("Amazon cleanup: " + checks + " assertions passed");
    }
}
