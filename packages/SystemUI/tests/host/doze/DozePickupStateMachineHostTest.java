/*
 * Copyright (C) 2026 The uwuAOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.doze;

/** Offline regression cases for pickup candidate deadlines and gesture epochs. */
public final class DozePickupStateMachineHostTest {
    private static final long NS_PER_MS = 1_000_000L;

    public static void main(String[] args) {
        deadlineStaysBoundToReceipt();
        elapsedTimeDoesNotRearm();
        disablesPendingWindowWithoutForgettingConsumedMotion();
        rejectsInvalidAndPreviouslyEvaluatedTimestamps();
        System.out.println("DozePickupStateMachineHostTest: all cases passed");
    }

    private static void deadlineStaysBoundToReceipt() {
        DozePickupStateMachine state = new DozePickupStateMachine();
        state.setListening(true, ns(1000));
        expect(state.begin(ns(1040), ns(1100), ns(1120)), "candidate accepted");
        expect(state.deadlineNs() == ns(1350), "deadline is 250 ms from receipt");
        expect(!state.begin(ns(1150), ns(1150), ns(1150)), "in-flight window is not restarted");
        expect(!state.confirm(ns(1030), ns(1340), ns(1350)), "at-deadline delivery is too late");
        state.finish();
        expect(state.state() == DozePickupStateMachine.State.ARMED, "expired first window");
        expect(!state.begin(ns(1500), ns(1510), ns(1760)), "queued work cannot extend deadline");
    }

    private static void elapsedTimeDoesNotRearm() {
        DozePickupStateMachine state = new DozePickupStateMachine();
        state.setListening(true, ns(1000));
        confirmFirstMotion(state);
        expect(!state.begin(ns(2350), ns(2360), ns(2360)), "cooldown blocks early candidates");
        expect(state.state() == DozePickupStateMachine.State.REFRACTORY, "not rearmed by time");
        expect(state.begin(ns(2600), ns(2610), ns(2610)), "late hint may probe for fresh evidence");
        expect(!state.confirm(ns(1140), ns(2660), ns(2670)), "old evidence cannot be replayed");
        expect(!state.confirm(state.evidenceSinceNs(), ns(2660), ns(2670)),
                "a motion touching the excluded epoch is not fresh");
        state.finish();
        expect(state.state() == DozePickupStateMachine.State.REFRACTORY,
                "probe without fresh evidence stays refractory");
        expect(state.begin(ns(2900), ns(2910), ns(2920)), "new candidate probes again");
        expect(state.confirm(ns(2850), ns(3000), ns(3010)), "measured new motion can confirm");
        expect(state.state() == DozePickupStateMachine.State.TRIGGERED, "one success transition");
        expect(!state.confirm(ns(2850), ns(3000), ns(3010)), "cannot confirm twice in one window");
    }

    private static void disablesPendingWindowWithoutForgettingConsumedMotion() {
        DozePickupStateMachine state = new DozePickupStateMachine();
        expect(!state.begin(ns(1000), ns(1010), ns(1010)), "disabled state ignores candidates");
        state.setListening(true, ns(1000));
        confirmFirstMotion(state);
        long consumedFloorNs = state.evidenceSinceNs();
        expect(state.begin(ns(2900), ns(2910), ns(2920)), "new probe starts");
        state.setListening(false, ns(2950));
        expect(!state.confirm(ns(2850), ns(2960), ns(2970)), "disable cancels pending confirmation");
        state.finish();
        expect(state.state() == DozePickupStateMachine.State.DISABLED, "cleanup remains disabled");
        state.setListening(true, ns(3000));
        expect(state.state() == DozePickupStateMachine.State.REFRACTORY,
                "listen cycle does not rearm consumed motion");
        expect(state.evidenceSinceNs() == consumedFloorNs, "consumed epoch survives listen cycle");
    }

    private static void rejectsInvalidAndPreviouslyEvaluatedTimestamps() {
        DozePickupStateMachine state = new DozePickupStateMachine();
        state.setListening(true, ns(1000));
        expect(!state.begin(0, ns(1010), ns(1010)), "no fabricated timestamps");
        expect(!state.begin(ns(990), ns(1020), ns(1020)), "event predates enabling");
        expect(!state.begin(ns(1100), ns(1010), ns(1010)), "sensor event is in the future");
        expect(!state.begin(ns(1000), ns(2400), ns(2400)), "outside recognition history");
        expect(state.begin(ns(1020), ns(1335), ns(1340)), "315ms QTI delivery lag is valid");
        expect(state.deadlineNs() == ns(1585), "lag is not charged against receipt budget");
        state.finish();
        expect(state.begin(ns(1510), ns(1520), ns(1520)), "current sensor event");
        state.finish();
        expect(!state.begin(ns(1510), ns(1560), ns(1560)), "same event is not reconsidered");
    }

    private static void confirmFirstMotion(DozePickupStateMachine state) {
        expect(state.begin(ns(1040), ns(1050), ns(1050)), "start first evaluation");
        expect(state.confirm(ns(1010), ns(1200), ns(1210)), "confirm first motion");
        state.finish();
    }

    private static long ns(long millis) {
        return millis * NS_PER_MS;
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
