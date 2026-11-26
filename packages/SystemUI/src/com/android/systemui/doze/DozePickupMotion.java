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

import static com.android.systemui.doze.DozePickupMotionFeatures.ms;

/** Recognizes recent pickup motion when the hardware asks for an evaluation. */
final class DozePickupMotion {
    enum Path { NONE, TILT, TRANSLATION, POCKET, WALKING_VIEW }

    static final long WINDOW_MS = 1_200;
    private static final long TILT_HOLD_NS = ms(180);
    private static final long POCKET_HOLD_NS = ms(170);
    private static final long WALKING_HOLD_NS = ms(280);
    private static final long TRANSLATION_HOLD_NS = ms(240);

    private final DozePickupMotionFeatures mFeatures = new DozePickupMotionFeatures();
    private final float mTiltDot;
    private final float mPocketDot;
    private final float mWalkingDot;
    private final float mAcceleration;
    private long mMotionStartNs = -1;
    private long mMotionEndNs = -1;

    DozePickupMotion(float tiltDegrees, float acceleration) {
        mTiltDot = cosine(tiltDegrees);
        mPocketDot = cosine(Math.max(14f, tiltDegrees * 0.8f));
        mWalkingDot = cosine(Math.max(25f, tiltDegrees * 1.25f));
        mAcceleration = acceleration;
    }

    /** All timestamps use sensor elapsed realtime; {@code sinceNs} is an exclusive cutoff. */
    Path classify(DozePickupMotionFeatures.Samples samples, long candidateNs, long nowNs,
            long sinceNs, long nearToFarNs, boolean near) {
        mMotionStartNs = -1;
        mMotionEndNs = -1;
        if (near || candidateNs <= 0 || nowNs < candidateNs || nowNs - candidateNs > ms(WINDOW_MS)
                || !mFeatures.load(samples, nowNs, sinceNs)) {
            return Path.NONE;
        }
        // The hint's timestamp is only a validity check, never evidence of motion.
        final long first = nowNs - ms(WINDOW_MS);
        final int held = mFeatures.heldPose(false);
        if (mFeatures.heldFor(held, POCKET_HOLD_NS) && nearToFarNs > 0 && nearToFarNs > sinceNs
                && nearToFarNs >= first && nearToFarNs <= nowNs) {
            final int turn = mFeatures.turn(held, first, mPocketDot, ms(100), 0.982f, 4.4f);
            if (turn >= 0) {
                final int pull = mFeatures.withdrawal(turn, held, nearToFarNs, mAcceleration);
                if (pull >= 0 && mFeatures.timestamp(pull) >= first) {
                    return confirm(Path.POCKET, pull);
                }
            }
        }

        mFeatures.findSteps(mAcceleration);
        if (mFeatures.heldFor(held, TILT_HOLD_NS)) {
            final int turn = mFeatures.turn(held, first, mTiltDot, ms(130), 0.995f, 2.5f);
            if (turn >= 0 && !mFeatures.hasCadence(mFeatures.timestamp(turn))) {
                return confirm(Path.TILT, turn);
            }
        }
        if (mFeatures.heldFor(held, TRANSLATION_HOLD_NS)
                && !mFeatures.hasCadence(mFeatures.latestNs())) {
            final int lift = mFeatures.translation(held, first, mAcceleration, TRANSLATION_HOLD_NS);
            if (lift >= 0) {
                return confirm(Path.TRANSLATION, lift);
            }
        }

        final int walkingHold = mFeatures.heldPose(true);
        if (mFeatures.heldFor(walkingHold, WALKING_HOLD_NS)) {
            final int turn = mFeatures.turn(walkingHold, first, mWalkingDot, ms(200), 0.965f, 3.3f);
            if (turn >= 0 && mFeatures.hasCadence(mFeatures.timestamp(turn))) {
                return confirm(Path.WALKING_VIEW, turn);
            }
        }
        return Path.NONE;
    }

    /** A recent, settled tilt into a viewing pose, without a hardware hint. */
    boolean classifyHistoryTilt(DozePickupMotionFeatures.Samples samples, long nowNs,
            long sinceNs) {
        if (!mFeatures.load(samples, nowNs, sinceNs)) return false;
        final int held = mFeatures.heldPose(false);
        if (!mFeatures.heldFor(held, TILT_HOLD_NS) || !mFeatures.heldInView(mTiltDot)) {
            return false;
        }
        final int turn = mFeatures.turn(held, nowNs - ms(WINDOW_MS), mTiltDot,
                ms(130), 0.995f, 2.5f);
        if (turn < 0) return false;
        mFeatures.findSteps(mAcceleration);
        if (mFeatures.hasCadence(mFeatures.timestamp(turn))) return false;
        confirm(Path.TILT, turn);
        return true;
    }

    /** First corroborating motion and last observed hold sample, or -1 after a miss. */
    long motionStartNs() {
        return mMotionStartNs;
    }

    long motionEndNs() {
        return mMotionEndNs;
    }

    private Path confirm(Path path, int onset) {
        mMotionStartNs = mFeatures.timestamp(onset);
        mMotionEndNs = mFeatures.latestNs();
        return path;
    }

    private static float cosine(float degrees) {
        return (float) Math.cos(Math.toRadians(degrees));
    }
}
