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

import static com.android.systemui.doze.DozePickupMotion.Path.NONE;
import static com.android.systemui.doze.DozePickupMotion.Path.POCKET;
import static com.android.systemui.doze.DozePickupMotion.Path.TILT;
import static com.android.systemui.doze.DozePickupMotion.Path.TRANSLATION;
import static com.android.systemui.doze.DozePickupMotion.Path.WALKING_VIEW;

import com.android.systemui.doze.DozePickupMotion.Path;
import com.android.systemui.doze.DozePickupMotionFeatures.Samples;

/** Deterministic synthetic traces for the production classifier, not field recordings. */
public final class DozePickupMotionHostTest {
    private static final long ORIGIN_NS = 10_000_000_000L;
    private static final float G = 9.80665f;

    public static void main(String[] args) {
        quietAndImpacts();
        tiltAcrossPoses();
        translationAndLookalikes();
        pocketRequiresWithdrawalAndUncover();
        walkingRequiresViewChange();
        oscillationAndFaceDown();
        candidateAssociationAndStaleness();
        invalidOrInterruptedStream();
        fifoOverlapAndBounds();
        consumedMotionNeedsFreshEvidence();
        System.out.println("DozePickupMotionHostTest: all cases passed");
    }

    private static void quietAndImpacts() {
        final Samples samples = new Samples();
        for (int angle : new int[] {0, 42, 87, -45}) {
            samples.clear();
            pose(samples, -480, 480, angle, 0);
            check("desk or stationary stand at " + angle, NONE, samples, 0, 420);
        }
        samples.clear();
        pose(samples, -480, -20, 35, 0);
        pose(samples, 0, 480, 79, 0);
        check("abrupt clack against an angled stand", NONE, samples, 0, 420);

        samples.clear();
        for (int ms = -400; ms <= 440; ms += 20) {
            final float vibration = ms >= -80 && ms <= 160 ? (ms % 40 == 0 ? 1.5f : -1.5f) : 0;
            sample(samples, ms, ms == 0 ? 27 : 6, ms == 0 ? 5 : vibration);
        }
        check("isolated knock and alternating motor vibration", NONE, samples, 0, 420);
    }

    private static void tiltAcrossPoses() {
        check("flat desk to inclined view", TILT, turn(-120, 80, 0, 44, 420), 0, 360);
        check("portrait to a past-vertical view without an absolute pose target", TILT,
                turn(-120, 80, 62, 111, 420), 0, 360);
        check("opposite-side incline", TILT, turn(-120, 80, -22, -67, 420), 0, 360);
        final Samples landscape = new Samples();
        for (int ms = -460; ms <= 360; ms += 20) {
            final double radians = Math.toRadians(ramp(ms, -120, 80, 26, 67));
            final float transverse = (float) (G * Math.sin(radians) / Math.sqrt(2));
            landscape.add(time(ms), transverse, -transverse, (float) (G * Math.cos(radians)));
        }
        check("landscape view uses all three axes", TILT, landscape, 0, 360);
        check("small change in stand inclination", NONE, turn(-120, 80, 42, 51, 440), 0, 400);
        check("tilt without observed settled tail", NONE, turn(-120, 80, 0, 44, 100), 0, 100);
    }

    private static void translationAndLookalikes() {
        check("near-level translation", TRANSLATION, lift(7), 0, 260);
        check("upright translation", TRANSLATION, lift(78), 0, 260);
        check("horizontal removal from a vertical stand", TRANSLATION,
                translation(89, 2.4f, 0, 0, -2.1f, 0, 0), 0, 260);
        final Samples diagonal = translation(38, 1.6f, -1.2f, 1.25f, -1.4f, 1.1f, -1.15f);
        check("diagonal translation through all three axes", TRANSLATION, diagonal, 0, 260);
        check("the same translation with a much earlier hint", TRANSLATION, diagonal, -880, 260);
        check("orthogonal impulse isn't braking", NONE,
                translation(89, 2.4f, 0, 0, 0, -2.2f, 0), 0, 260);
        check("unbalanced braking impulse", NONE,
                translation(89, 3.0f, 0, 0, -1.2f, 0, 0), 0, 260);
        check("horizontal drive and brake too violent to be a translation", NONE,
                translation(89, 8f, 0, 0, -8f, 0, 0), 0, 260);
        check("face-down end blocks lateral translation", NONE,
                translation(175, 2.4f, 0, 0, -2.1f, 0, 0), 0, 260);

        final Samples samples = new Samples();
        pose(samples, -540, -280, 23, 0);
        pose(samples, -260, -140, 23, 1.8f);
        pose(samples, -120, 360, 23, 0);
        check("single ascent lobe", NONE, samples, 0, 280);

        samples.clear();
        pose(samples, -540, -280, 23, 0);
        pose(samples, -260, -160, 23, -1.8f);
        pose(samples, -140, -40, 23, 1.8f);
        pose(samples, -20, 360, 23, 0);
        check("reverse gravity-axis translation is also valid", TRANSLATION, samples, 0, 280);

        samples.clear();
        pose(samples, -540, -280, 23, 0);
        pose(samples, -260, -200, 23, 1.8f);
        pose(samples, -180, -120, 23, -1.8f);
        pose(samples, -100, -40, 23, 1.8f);
        pose(samples, -20, 40, 23, -1.8f);
        pose(samples, 60, 380, 23, 0);
        check("repeated translation lobes then rest", NONE, samples, 0, 380);

        samples.clear();
        pose(samples, -540, -280, 0, 0);
        pose(samples, -260, -160, 13, 0);
        pose(samples, -140, -40, -13, 0);
        pose(samples, -20, 380, 0, 0);
        check("pure angular oscillation mimics lateral acceleration but not translation", NONE,
                samples, 0, 280);
    }

    private static void pocketRequiresWithdrawalAndUncover() {
        final Samples samples = new Samples();
        for (int ms = -500; ms <= 460; ms += 20) {
            sample(samples, ms, ramp(ms, -40, 180, -100, -82),
                    ms >= -20 && ms <= 80 ? 1.6f : 0);
        }
        check("withdrawal with a small directed turn and near-to-far", POCKET,
                samples, 0, 430, -1, time(20), false);
        check("same small turn without an uncover", NONE, samples, 0, 430);
        check("still covered", NONE, samples, 0, 430, -1, time(20), true);
        check("stale uncover", NONE, samples, 0, 430, -1, time(-600), false);
        check("future uncover", NONE, samples, 0, 430, -1, time(440), false);
        check("uncover and a small tilt without withdrawal", NONE,
                turn(-40, 180, -100, -82, 460), 0, 430, -1, time(20), false);

        samples.clear();
        pose(samples, -500, 460, -100, 0);
        check("proximity-only uncover", NONE, samples, 0, 430, -1, time(20), false);
    }

    private static void walkingRequiresViewChange() {
        check("walking continues after raising phone to a held view", WALKING_VIEW,
                walking(true, false), 0, 410);
        check("walking to a view, then stops", WALKING_VIEW, walking(true, true), 0, 410);
        check("delayed QTI can confirm a held walking view within 250 ms of receipt", WALKING_VIEW,
                walking(true, false, -340), 0, 220);
        check("walking verdict is independent of the hint's phase", WALKING_VIEW,
                walking(true, false, -340), -920, 220);
        check("ordinary carried gait with small device sway", NONE, walking(false, false), 0, 410);
        check("stopping with the phone still in its carry pose", NONE,
                walking(false, true), 0, 410);
        check("uncover while carrying does not substitute for a view", NONE,
                walking(false, false), 0, 410, -1, time(20), false);

        final Samples swinging = new Samples();
        for (int ms = -2400; ms <= 420; ms += 20) {
            final float angle = (float) (23 * Math.sin((ms + 2400) * 2 * Math.PI / 440));
            sample(swinging, ms, angle, step(ms));
        }
        check("large periodic arm swing without a held view", NONE, swinging, 0, 410);
    }

    private static void oscillationAndFaceDown() {
        final Samples samples = turn(-180, 0, 0, 42, 180);
        for (int ms = 200; ms <= 560; ms += 20) {
            sample(samples, ms, ramp(ms, 180, 360, 42, -34), 0);
        }
        check("briefly stable tilt, then renewed motion in the same candidate", NONE,
                samples, 0, 560);
        check("ending face-down does not wake", NONE, turn(-180, 100, 105, 165, 430), 0, 410);
        check("starts face-down but ends in a view", TILT,
                turn(-180, 100, 165, 105, 430), 0, 410);
    }

    private static void candidateAssociationAndStaleness() {
        final Samples samples = turn(-340, -140, 8, 59, 1240);
        final DozePickupMotion motion = newMotion();
        expect(motion.classify(samples, time(0), time(80), -1, -1, false) == TILT,
                "delayed QTI arrival uses motion before the raw callback");
        expect(motion.motionStartNs() < time(-260) && motion.motionEndNs() == time(80),
                "the evidence records the observed pretrigger onset and held sample");
        check("early hint and delayed hint produce the same result", TILT, samples, -960, 80);
        check("hint after all motion also gives the same result", TILT, samples, 80, 80);
        check("old motion cannot be replayed just because the phone is still held", NONE,
                samples, 900, 940);
        final Samples recent = turn(740, 920, 0, 48, 1240);
        check("hint deadline is inclusive", TILT, recent, 0, 1200);
        check("stale hint rejected even with a genuinely fresh movement", NONE,
                recent, 0, 1201);
        check("future hint", NONE, samples, 100, 80);
        check("future data cannot complete a hold", NONE, samples, -120, -60);
        check("motion long after a still-valid hint is eligible", TILT,
                turn(480, 680, 0, 51, 1080), 0, 1060);

        final Samples mixedRate = new Samples();
        for (int ms = -600; ms < 0; ms += 40) {
            sample(mixedRate, ms, ramp(ms, -240, 40, 14, 58), 0);
        }
        for (int ms = 0; ms <= 240; ms += 20) {
            sample(mixedRate, ms, ramp(ms, -240, 40, 14, 58), 0);
        }
        check("25 Hz FIFO before receipt and 50 Hz live tail", TILT, mixedRate, 0, 240);

        final Samples dropped = turn(-220, -40, 8, 59, 240);
        check("delivery time alone does not extend the last sample", NONE, dropped, 0, 340);
        sample(dropped, 360, 59, 0);
        check("a sensor gap does not count toward a hold", NONE, dropped, 0, 360);
    }

    private static void invalidOrInterruptedStream() {
        final Samples samples = new Samples();
        pose(samples, -480, -180, 0, 0);
        samples.add(time(-160), Float.NaN, 0, G);
        pose(samples, -140, 420, 52, 0);
        check("nonfinite sample breaks continuity", NONE, samples, 0, 420);

        samples.clear();
        pose(samples, -480, -180, 0, 0);
        samples.add(time(-160), 0, 0, 0);
        samples.add(time(-140), 0, Float.POSITIVE_INFINITY, G);
        samples.add(time(-120), 0, 0, 70f);
        pose(samples, -100, 420, 48, 0);
        check("zero gravity, infinity and saturation cannot seed a pose", NONE, samples, 0, 420);

        samples.clear();
        pose(samples, -480, -220, 0, 0);
        pose(samples, -100, 400, 46, 0);
        check("missing data cannot connect two otherwise held poses", NONE, samples, 0, 400);
        samples.clear();
        check("empty stream", NONE, samples, 0, 200);
    }

    private static void fifoOverlapAndBounds() {
        final Samples merged = new Samples();
        pose(merged, 0, 260, 55, 0);
        for (int ms = -460; ms <= 0; ms += 20) {
            sample(merged, ms, ramp(ms, -220, 0, 4, 55), 0);
        }
        final int count = merged.size();
        // Conflicting duplicates must not replace the first observed sample or extend a hold.
        sample(merged, 0, -100, 8);
        sample(merged, -20, -100, 8);
        expect(merged.size() == count && merged.latestNs() == time(260), "FIFO deduplicated");
        check("FIFO prehistory delivered after live tail", TILT, merged, 0, 260);
        sample(merged, 280, -120, 9);
        check("future tail ignored at the current evaluation", TILT, merged, 0, 260);

        final Samples wrapped = new Samples();
        for (int ms = -4000; ms < 0; ms += 5) {
            sample(wrapped, ms, 32, 0);
        }
        final int capacity = wrapped.size();
        sample(wrapped, -7, 32, 0);
        sample(wrapped, -3500, -120, 9);
        expect(wrapped.size() == capacity && capacity <= 320, "history is bounded");
        expect(wrapped.latestNs() == time(-5), "the newest sample survives late inserts");
        expect(wrapped.timeAt(0) >= time(-3005), "expired history was evicted");
        for (int i = 1; i < wrapped.size(); i++) {
            expect(wrapped.timeAt(i) > wrapped.timeAt(i - 1), "history stays timestamp ordered");
        }
        wrapped.clear();
        expect(wrapped.size() == 0 && wrapped.latestNs() == -1, "clear resets sample epoch");
    }

    private static void consumedMotionNeedsFreshEvidence() {
        final Samples samples = turn(-220, -20, 5, 56, 200);
        final DozePickupMotion motion = newMotion();
        expect(motion.classify(samples, time(0), time(200), -1, -1, false) == TILT,
                "initial motion accepted");
        final long consumed = motion.motionEndNs();
        pose(samples, 220, 400, 56, 0);
        expect(motion.classify(samples, time(40), time(400), consumed, -1, false) == NONE,
                "duplicate candidate cannot borrow consumed samples");
        expect(motion.motionStartNs() == -1 && motion.motionEndNs() == -1,
                "miss cannot leave evidence for state to consume");
        for (int ms = 420; ms <= 880; ms += 20) {
            sample(samples, ms, ramp(ms, 400, 620, 56, -3), 0);
        }
        expect(motion.classify(samples, time(460), time(880), consumed, -1, false) == TILT,
                "a distinctly new movement rearms with its own baseline and hold");
        expect(motion.motionStartNs() > consumed && motion.motionEndNs() == time(880),
                "fresh evidence is strictly newer than the consumed epoch");
    }

    private static Samples turn(int start, int end, float from, float to, int last) {
        final Samples samples = new Samples();
        for (int ms = start - 380; ms <= last; ms += 20) {
            sample(samples, ms, ramp(ms, start, end, from, to), 0);
        }
        return samples;
    }

    private static Samples lift(int angle) {
        final Samples samples = new Samples();
        pose(samples, -540, -280, angle, 0);
        pose(samples, -260, -160, angle, 1.7f);
        pose(samples, -140, -40, angle, -1.5f);
        pose(samples, -20, 380, angle, 0);
        return samples;
    }

    private static Samples translation(int angle, float ax, float ay, float az,
            float bx, float by, float bz) {
        final Samples samples = new Samples();
        final double radians = Math.toRadians(angle);
        final float gravityY = G * (float) Math.sin(radians);
        final float gravityZ = G * (float) Math.cos(radians);
        for (int ms = -540; ms <= 380; ms += 20) {
            if (ms >= -260 && ms <= -160) {
                samples.add(time(ms), ax, gravityY + ay, gravityZ + az);
            } else if (ms >= -140 && ms <= -40) {
                samples.add(time(ms), bx, gravityY + by, gravityZ + bz);
            } else {
                samples.add(time(ms), 0, gravityY, gravityZ);
            }
        }
        return samples;
    }

    private static Samples walking(boolean raise, boolean stop) {
        return walking(raise, stop, -160);
    }

    private static Samples walking(boolean raise, boolean stop, int raiseStart) {
        final Samples samples = new Samples();
        for (int ms = -2400; ms <= 420; ms += 20) {
            final float angle = raise ? ramp(ms, raiseStart, raiseStart + 240, 3, 48) : 3;
            final float sway = (float) (2 * Math.sin((ms + 2400) * 2 * Math.PI / 440));
            sample(samples, ms, angle + sway, stop && ms >= 0 ? 0 : step(ms));
        }
        return samples;
    }

    private static float step(int ms) {
        final int phase = (ms + 2400) % 440;
        return phase <= 60 ? 1.6f : phase >= 100 && phase <= 140 ? -1.4f : 0;
    }

    private static float ramp(int ms, int start, int end, float from, float to) {
        final float fraction = Math.max(0f, Math.min(1f, (float) (ms - start) / (end - start)));
        return from + (to - from) * fraction;
    }

    private static void pose(Samples samples, int first, int last, float angle, float radial) {
        for (int ms = first; ms <= last; ms += 20) {
            sample(samples, ms, angle, radial);
        }
    }

    private static void sample(Samples samples, int ms, float angle, float radial) {
        final double radians = Math.toRadians(angle);
        samples.add(time(ms), 0, (G + radial) * (float) Math.sin(radians),
                (G + radial) * (float) Math.cos(radians));
    }

    private static long time(int ms) {
        return ORIGIN_NS + ms * 1_000_000L;
    }

    private static DozePickupMotion newMotion() {
        return new DozePickupMotion(20f, 1.1f);
    }

    private static void check(String name, Path expected, Samples samples, int candidate, int now) {
        check(name, expected, samples, candidate, now, -1, -1, false);
    }

    private static void check(String name, Path expected, Samples samples, int candidate, int now,
            long since, long far, boolean near) {
        final Path actual = newMotion().classify(samples, time(candidate), time(now), since,
                far, near);
        expect(actual == expected, name + ": expected " + expected + ", got " + actual);
    }

    private static void expect(boolean condition, String scenario) {
        if (!condition) {
            throw new AssertionError(scenario);
        }
    }
}
