// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.toolicious.homeonfire;

public final class UsageCursorTest {
    private static int checks;
    private static void check(boolean test, String label) {
        checks++;
        if (!test) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        UsageCursor c = new UsageCursor();
        check(c.begin(100000000, 1000) == 13600000, "Initial read uses bounded 24-hour seed");
        check(c.isSeeding(), "Initial query is seed-only");
        check(c.accept(99999000, 1, "a", "A"), "First event accepted");
        check(!c.isFresh(99999000), "Pre-start history is not fresh");
        c.finish();
        check(c.begin(100000250, 1250) <= 99998000, "Next query includes preceding event history");
        check(!c.isSeeding(), "Normal query is not seeding");
        check(!c.accept(99999000, 1, "a", "A"), "Overlapping old event deduplicated");
        check(c.accept(100000100, 1, "b", "B"), "New event accepted");
        check(c.isFresh(100000100), "New event marked fresh");
        check(!c.accept(100100000, 1, "c", "C"), "Future event rejected");
        c.finish();
        c.begin(99900000, 1500);
        check(c.isSeeding(), "Backward wall-clock change reseeds");
        check(!c.isFresh(99900000), "Clock-change boundary is historical");
        c.finish();
        c.begin(199900000, 1750);
        check(c.isSeeding(), "Forward wall-clock change reseeds");
        c.finish();
        c.reset(200000000);
        c.begin(200000000, 2000);
        check(c.isSeeding(), "Service/wake/configuration reset reseeds");

        UsageCursor delayed = new UsageCursor();
        delayed.begin(100000000, 1000);
        delayed.finish();
        // Simulate healthy polling before the firmware publishes an earlier event.
        for (int i = 1; i <= 12; i++) {
            delayed.begin(100000000 + i * 250, 1000 + i * 250);
            delayed.finish();
        }
        long lateEvent = 100000100;
        check(delayed.begin(100003250, 4250) <= lateEvent,
                "An activity published three seconds late remains in the queried interval");
        check(delayed.accept(lateEvent, 1, "late", "Home") && delayed.isFresh(lateEvent),
                "Delayed post-start activity is accepted as fresh");
        check(!delayed.accept(lateEvent, 1, "late", "Home"),
                "Extended lookback does not replay the same activity event");
        check(delayed.acceptObservation(lateEvent), "First chronological observation is usable");
        check(!delayed.acceptObservation(lateEvent - 50),
                "An older delayed Home cannot overwrite a newer foreground state");
        check(delayed.acceptObservation(lateEvent), "Equal-time transitions remain usable");
        delayed.reset(100003250);
        check(delayed.acceptObservation(lateEvent - 50),
                "Wake/service/clock reset allows a newly seeded timeline");
        System.out.println("UsageCursor: " + checks + " behavioral assertions passed");
    }
}
