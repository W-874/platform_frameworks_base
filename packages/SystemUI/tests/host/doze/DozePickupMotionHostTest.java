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

/** Offline regression cases for the production pickup classifier, using synthetic samples. */
public final class DozePickupMotionHostTest {
    private static final long START_NS = 10_000_000_000L;
    private static final long NS_PER_MS = 1_000_000L;
    private static final float G = 9.80665f;

    public static void main(String[] args) {
        stationaryFlatAndTilted();
        tiltObservedAfterTrigger();
        nearLevelLift();
        weakAndIsolatedExcursions();
        walkingLikeRecurrence();
        renewedMotionAfterQuiet();
        invalidAccelerationIsNotGravity();
        noBorrowedPretriggerOrAfterDeadlineSamples();
        droppedStreamCannotLookSettled();
        System.out.println("DozePickupMotionHostTest: all cases passed");
    }

    private static void stationaryFlatAndTilted() {
        DozePickupMotion flat = new DozePickupMotion(START_NS);
        // Approximate flat values in the previous SystemUI regression case, not a field trace.
        segment(flat, 0, 180, 0.03f, -0.11f, 9.56f);
        segment(flat, 200, 480, 0.02f, -0.12f, 9.55f);
        expect(!flat.isConfirmed() && !flat.isRecurrent(), "stationary desk");

        DozePickupMotion tilted = new DozePickupMotion(START_NS);
        sample(tilted, 0, 0, 0, G); // One stray sample is not an observed starting pose.
        segment(tilted, 20, 480, 0, 6f, 8f);
        expect(!tilted.isConfirmed(), "stationary inclined phone with an initial outlier");
    }

    private static void tiltObservedAfterTrigger() {
        DozePickupMotion motion = new DozePickupMotion(START_NS);
        // Registration can be late: all evidence here is still inside the triggered window.
        segment(motion, 200, 220, 0, 0, G);
        sample(motion, 240, 0, 2.5f, 9.5f);
        segment(motion, 260, 480, 0, 6f, 8f);
        expect(motion.isConfirmed(), "observable change to a settled incline");
    }

    private static void nearLevelLift() {
        DozePickupMotion motion = new DozePickupMotion(START_NS);
        levelLiftPulse(motion);
        segment(motion, 120, 480, 0.2f, 0.5f, G);
        expect(motion.isConfirmed(), "near-level lift already accelerating on first sample");
    }

    private static void weakAndIsolatedExcursions() {
        DozePickupMotion motion = new DozePickupMotion(START_NS);
        segment(motion, 0, 40, 0, 0, G + 0.7f);
        segment(motion, 60, 100, 0, 0, G - 0.7f);
        sample(motion, 120, 0, 0, G + 1.3f);
        sample(motion, 140, 0, 0, G - 1.3f);
        segment(motion, 160, 480, 0, 0, G);
        expect(!motion.isConfirmed(), "small desk vibration and one-sample spikes");

        DozePickupMotion singleLobe = new DozePickupMotion(START_NS);
        segment(singleLobe, 0, 40, 0, 0, G + 1.3f);
        segment(singleLobe, 60, 480, 0, 0, G);
        expect(!singleLobe.isConfirmed(), "one sustained excursion is not a lift");
    }

    private static void walkingLikeRecurrence() {
        DozePickupMotion motion = new DozePickupMotion(START_NS);
        levelLiftPulse(motion);
        segment(motion, 120, 160, 0, 0, G);
        segment(motion, 180, 220, 0, 0, G + 1.3f);
        segment(motion, 240, 280, 0, 0, G - 1.3f);
        segment(motion, 300, 480, 0, 0, G);
        expect(!motion.isConfirmed() && motion.isRecurrent(), "recurring steps then a quiet tail");
    }

    private static void renewedMotionAfterQuiet() {
        DozePickupMotion motion = new DozePickupMotion(START_NS);
        segment(motion, 0, 40, 0, 0, G);
        segment(motion, 60, 220, 0, 6f, 8f);
        segment(motion, 240, 480, 0, -6f, 8f);
        expect(!motion.isConfirmed() && motion.isRecurrent(), "settled incline then renewed sway");
    }

    private static void invalidAccelerationIsNotGravity() {
        DozePickupMotion motion = new DozePickupMotion(START_NS);
        segment(motion, 0, 40, 0, 0, G);
        segment(motion, 60, 200, 0, 17f, 6f);
        sample(motion, 220, Float.NaN, 0, G);
        segment(motion, 240, 480, 0, 0, G);
        expect(!motion.isConfirmed(), "high acceleration and non-finite samples");
    }

    private static void noBorrowedPretriggerOrAfterDeadlineSamples() {
        DozePickupMotion motion = new DozePickupMotion(START_NS);
        segment(motion, -60, -20, 0, 0, G);
        segment(motion, 0, 480, 0, 6f, 8f);
        expect(!motion.isConfirmed(), "no borrowed pre-trigger pose");

        DozePickupMotion stale = new DozePickupMotion(START_NS);
        segment(stale, 0, 40, 0, 0, G);
        // Duplicate and out-of-order events cannot create or extend an observation.
        sample(stale, 40, 0, 6f, 8f);
        sample(stale, 20, 0, 6f, 8f);
        segment(stale, 60, 360, 0, 0, G);
        segment(stale, 380, 620, 0, 6f, 8f);
        expect(stale.isConfirmed(), "exactly 120 ms of steady tilt inside the window");

        DozePickupMotion late = new DozePickupMotion(START_NS);
        segment(late, 0, 380, 0, 0, G);
        segment(late, 400, 620, 0, 6f, 8f);
        expect(!late.isConfirmed(), "post-deadline samples cannot complete the settle period");
    }

    private static void droppedStreamCannotLookSettled() {
        expect(!new DozePickupMotion(START_NS).isConfirmed(), "empty sensor window");
        DozePickupMotion motion = new DozePickupMotion(START_NS);
        levelLiftPulse(motion);
        segment(motion, 120, 300, 0, 0, G);
        expect(!motion.isConfirmed(), "stale tail despite sufficient quiet time");
        sample(motion, 480, 0, 0, G);
        expect(!motion.isConfirmed(), "large sampling gap does not count as quiet time");
    }

    private static void levelLiftPulse(DozePickupMotion motion) {
        segment(motion, 0, 40, 0, 0, G + 1.3f);
        segment(motion, 60, 100, 0, 0, G - 1.3f);
    }

    private static void segment(DozePickupMotion motion, int firstMs, int lastMs,
            float x, float y, float z) {
        for (int offsetMs = firstMs; offsetMs <= lastMs; offsetMs += 20) {
            sample(motion, offsetMs, x, y, z);
        }
    }

    private static void sample(DozePickupMotion motion, int offsetMs, float x, float y, float z) {
        motion.addSample(START_NS + offsetMs * NS_PER_MS, x, y, z);
    }

    private static void expect(boolean condition, String scenario) {
        if (!condition) {
            throw new AssertionError(scenario);
        }
    }
}
