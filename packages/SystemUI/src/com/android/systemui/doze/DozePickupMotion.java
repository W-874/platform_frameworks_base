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

import java.util.concurrent.TimeUnit;

/** Classifies one post-trigger accelerometer window; no pre-trigger pose is assumed. */
final class DozePickupMotion {
    static final long WINDOW_MS = 500;
    private static final long MAX_SAMPLE_GAP_NS = TimeUnit.MILLISECONDS.toNanos(90);
    private static final long MIN_REFERENCE_NS = TimeUnit.MILLISECONDS.toNanos(20);
    private static final long MIN_LOBE_NS = TimeUnit.MILLISECONDS.toNanos(35);
    private static final long MIN_QUIET_CANDIDATE_NS = TimeUnit.MILLISECONDS.toNanos(60);
    private static final long MIN_SETTLE_NS = TimeUnit.MILLISECONDS.toNanos(120);

    private static final float GRAVITY = 9.80665f;
    private static final float QUIET_TOLERANCE = 0.55f;
    private static final float LIFT_EXCURSION = 0.9f;
    private static final float FLAT_COSINE = 0.940f; // Approx. 20 degrees from flat.
    private static final float ROTATION_COSINE = 0.978f; // Approx. 12 degrees from first pose.
    private static final float QUIET_COSINE = 0.996f; // Approx. 5 degrees of pose drift.

    private final long mStartNs;
    private final long mEndNs;
    private long mLastSampleNs = -1;
    private boolean mDiscontinuous;
    private boolean mRecurrent;

    private boolean mHasReference;
    private float mReferenceX;
    private float mReferenceY;
    private float mReferenceZ;
    private long mQuietSinceNs = -1;
    private float mQuietX;
    private float mQuietY;
    private float mQuietZ;
    private boolean mQuietIsPickup;

    private int mLobeSign;
    private long mLobeSinceNs;
    private boolean mSawAscent;
    private boolean mSawBraking;

    DozePickupMotion(long startNs) {
        mStartNs = startNs;
        mEndNs = startNs + TimeUnit.MILLISECONDS.toNanos(WINDOW_MS);
    }

    void addSample(long timestampNs, float x, float y, float z) {
        if (timestampNs < mStartNs || timestampNs > mEndNs || timestampNs <= mLastSampleNs
                || mDiscontinuous || mRecurrent) {
            return;
        }
        if (mLastSampleNs >= 0 && timestampNs - mLastSampleNs > MAX_SAMPLE_GAP_NS) {
            mDiscontinuous = true;
            return;
        }
        long previousSampleNs = mLastSampleNs;
        mLastSampleNs = timestampNs;

        float magnitudeSquared = x * x + y * y + z * z;
        if (!(magnitudeSquared >= 7 * 7 && magnitudeSquared <= 13 * 13)) {
            updateLobe(timestampNs, 0);
            endQuietPeriod(previousSampleNs);
            return;
        }
        float magnitude = (float) Math.sqrt(magnitudeSquared);
        x /= magnitude;
        y /= magnitude;
        z /= magnitude;
        boolean level = Math.abs(z) >= FLAT_COSINE;
        float gravityDelta = magnitude - GRAVITY;
        // A level lift can show acceleration against gravity followed by braking, without tilt.
        int lobeSign = 0;
        if (level) {
            if (gravityDelta >= LIFT_EXCURSION) {
                lobeSign = 1;
            } else if (gravityDelta <= -LIFT_EXCURSION) {
                lobeSign = -1;
            }
        }
        updateLobe(timestampNs, lobeSign);

        if (Math.abs(gravityDelta) > QUIET_TOLERANCE) {
            endQuietPeriod(previousSampleNs);
            return;
        }
        // Only near-gravity samples can establish a pose. Ignore isolated baseline samples.
        boolean pickup = level ? mSawBraking : mHasReference
                && x * mReferenceX + y * mReferenceY + z * mReferenceZ <= ROTATION_COSINE;
        if (mQuietSinceNs >= 0 && (mQuietIsPickup != pickup
                || x * mQuietX + y * mQuietY + z * mQuietZ < QUIET_COSINE)) {
            endQuietPeriod(previousSampleNs);
        }
        if (mQuietSinceNs < 0) {
            mQuietSinceNs = timestampNs;
            mQuietX = x;
            mQuietY = y;
            mQuietZ = z;
            mQuietIsPickup = pickup;
        } else if (!mHasReference && timestampNs - mQuietSinceNs >= MIN_REFERENCE_NS) {
            mHasReference = true;
            mReferenceX = mQuietX;
            mReferenceY = mQuietY;
            mReferenceZ = mQuietZ;
        }
    }

    boolean isConfirmed() {
        return !mDiscontinuous && !mRecurrent && mQuietIsPickup
                && mLastSampleNs - mQuietSinceNs >= MIN_SETTLE_NS
                && mEndNs - mLastSampleNs <= MAX_SAMPLE_GAP_NS;
    }

    boolean isRecurrent() {
        return mRecurrent;
    }

    private void endQuietPeriod(long previousSampleNs) {
        if (mQuietIsPickup && previousSampleNs - mQuietSinceNs >= MIN_QUIET_CANDIDATE_NS) {
            mRecurrent = true;
        }
        mQuietSinceNs = -1;
        mQuietIsPickup = false;
    }

    private void updateLobe(long timestampNs, int sign) {
        if (sign != mLobeSign) {
            mLobeSign = sign;
            mLobeSinceNs = timestampNs;
        } else if (sign != 0 && timestampNs - mLobeSinceNs >= MIN_LOBE_NS) {
            if (sign > 0) {
                mRecurrent |= mSawBraking;
                mSawAscent = true;
            } else if (mSawAscent) {
                mSawBraking = true;
            }
        }
    }
}
