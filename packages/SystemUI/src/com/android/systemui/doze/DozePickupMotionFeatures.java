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

/** Candidate-only features from a contiguous slice of the accelerometer history. */
final class DozePickupMotionFeatures {
    private static final long HISTORY_NS = ms(3_000);
    private static final long MAX_GAP_NS = ms(90);
    // Allow two overlapping 50 Hz listeners during FIFO-to-live handoff.
    private static final int CAPACITY = 320;
    private static final float G = 9.80665f;
    private static final float QUIET_RADIAL = 0.70f;
    private static final float FACE_DOWN_Z = -0.86f;
    private static final float TRANSLATION_QUIET_SQUARED = 0.75f * 0.75f;
    private static final float TRANSLATION_SHOCK_SQUARED = 5f * 5f;

    static final class Samples {
        private final long[] mTime = new long[CAPACITY];
        private final float[] mX = new float[CAPACITY];
        private final float[] mY = new float[CAPACITY];
        private final float[] mZ = new float[CAPACITY];
        private int mHead;
        private int mSize;

        /** Appends live events, or inserts an older FIFO event still in the history. */
        void add(long ns, float x, float y, float z) {
            if (ns <= 0) {
                return;
            }
            final long latest = latestNs();
            final long cutoff = Math.max(latest, ns) - HISTORY_NS;
            if (ns < cutoff) {
                return;
            }
            while (mSize != 0 && timeAt(0) < cutoff) {
                dropOldest();
            }
            int at = mSize;
            if (ns <= latest) {
                at = lowerBound(ns);
                if (at < mSize && timeAt(at) == ns) {
                    return;
                }
            }
            if (mSize == CAPACITY) {
                if (at == 0) {
                    return;
                }
                dropOldest();
                at--;
            }
            for (int i = mSize; i > at; i--) {
                final int from = slot(i - 1);
                final int to = slot(i);
                mTime[to] = mTime[from];
                mX[to] = mX[from];
                mY[to] = mY[from];
                mZ[to] = mZ[from];
            }
            final int slot = slot(at);
            final float squared = x * x + y * y + z * z;
            mTime[slot] = ns;
            // Invalid samples separate valid segments; they cannot extend a quiet period.
            mX[slot] = squared >= 1f && squared <= 900f ? x : Float.NaN;
            mY[slot] = y;
            mZ[slot] = z;
            mSize++;
        }

        void clear() {
            mHead = 0;
            mSize = 0;
        }

        int size() {
            return mSize;
        }

        long latestNs() {
            return mSize == 0 ? -1 : timeAt(mSize - 1);
        }

        private void dropOldest() {
            mHead = slot(1);
            mSize--;
        }

        private int slot(int index) {
            final int slot = mHead + index;
            return slot < CAPACITY ? slot : slot - CAPACITY;
        }

        long timeAt(int index) {
            return mTime[slot(index)];
        }

        private int lowerBound(long time) {
            int low = 0;
            int high = mSize;
            while (low < high) {
                final int mid = (low + high) >>> 1;
                if (timeAt(mid) < time) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }
    }

    private final long[] mTime = new long[CAPACITY];
    private final float[] mX = new float[CAPACITY];
    private final float[] mY = new float[CAPACITY];
    private final float[] mZ = new float[CAPACITY];
    private final float[] mMagnitude = new float[CAPACITY];
    private final int[] mSteps = new int[CAPACITY];
    private int mCount;
    private int mStepCount;
    private float mHeldX;
    private float mHeldY;
    private float mHeldZ;

    boolean load(Samples samples, long now, long since) {
        final int end = now >= samples.latestNs() ? samples.size()
                : samples.lowerBound(now + 1);
        if (end == 0 || now - samples.timeAt(end - 1) > MAX_GAP_NS) {
            return false;
        }
        int first = end;
        long next = samples.timeAt(end - 1);
        while (first > 0) {
            final long timestamp = samples.timeAt(first - 1);
            if (timestamp <= since || now - timestamp > HISTORY_NS || next - timestamp > MAX_GAP_NS
                    || Float.isNaN(samples.mX[samples.slot(first - 1)])) {
                break;
            }
            first--;
            next = timestamp;
        }
        mCount = end - first;
        if (mCount < 3) {
            return false;
        }
        for (int i = 0; i < mCount; i++) {
            final int slot = samples.slot(first + i);
            final float x = samples.mX[slot];
            final float y = samples.mY[slot];
            final float z = samples.mZ[slot];
            final float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
            mTime[i] = samples.mTime[slot];
            mMagnitude[i] = magnitude;
            mX[i] = x / magnitude;
            mY[i] = y / magnitude;
            mZ[i] = z / magnitude;
        }
        return true;
    }

    long latestNs() {
        return mTime[mCount - 1];
    }

    long timestamp(int index) {
        return mTime[index];
    }

    /** Find the start of the terminal held pose, allowing radial steps only in walking mode. */
    int heldPose(boolean walking) {
        final int last = mCount - 1;
        float x = 0;
        float y = 0;
        float z = 0;
        int i = last;
        do {
            x += mX[i];
            y += mY[i];
            z += mZ[i];
            i--;
        } while (i >= 0 && mTime[last] - mTime[i] <= ms(100));
        final float norm = (float) Math.sqrt(x * x + y * y + z * z);
        if (norm == 0) {
            return -1;
        }
        mHeldX = x / norm;
        mHeldY = y / norm;
        mHeldZ = z / norm;
        if (mHeldZ < FACE_DOWN_Z) {
            return -1;
        }
        final float cone = walking ? 0.988f : 0.995f; // 9 or 6 degrees
        final float radial = walking ? 3.3f : QUIET_RADIAL;
        i = last;
        while (i >= 0 && Math.abs(mMagnitude[i] - G) <= radial
                && dot(i, mHeldX, mHeldY, mHeldZ) >= cone) {
            i--;
        }
        return i + 1;
    }

    boolean heldFor(int start, long duration) {
        return start >= 0 && start < mCount && latestNs() - mTime[start] >= duration;
    }

    /** Marks sustained, separated footfall-sized pulses, independent of device orientation. */
    void findSteps(float acceleration) {
        mStepCount = 0;
        final float threshold = Math.max(0.9f, acceleration);
        int start = -1;
        for (int i = 0; i < mCount; i++) {
            if (mMagnitude[i] - G < threshold * (start < 0 ? 1f : 0.6f)) {
                start = -1;
            } else if (start < 0) {
                start = i;
            } else if (mTime[i] - mTime[start] >= ms(30)) {
                if (mStepCount == 0 || mTime[start] - mTime[mSteps[mStepCount - 1]] >= ms(180)) {
                    mSteps[mStepCount++] = start;
                }
            }
        }
    }

    boolean hasCadence(long before) {
        for (int i = mStepCount - 1; i >= 2; i--) {
            final long last = mTime[mSteps[i]];
            if (last >= before) {
                continue;
            }
            if (before - last > ms(950)) {
                break;
            }
            final long previous = mTime[mSteps[i - 1]];
            final long interval = last - previous;
            final long earlierInterval = previous - mTime[mSteps[i - 2]];
            if (interval >= ms(250) && interval <= ms(920)
                    && earlierInterval >= ms(250) && earlierInterval <= ms(920)
                    && Math.abs(interval - earlierInterval) <= Math.max(interval, earlierInterval)
                            * 0.32f) {
                return true;
            }
        }
        return false;
    }

    /** Finds a directional turn between two held poses, not a shock or a returning sway. */
    int turn(int held, long earliest, float minimumDot, long baselineDuration,
            float baselineCone, float radialLimit) {
        int base = -1;
        for (int i = 0; i < held; i++) {
            if (mTime[i] < earliest - baselineDuration - MAX_GAP_NS) {
                continue;
            }
            if (Math.abs(mMagnitude[i] - G) > radialLimit) {
                base = -1;
                continue;
            }
            if (base < 0 || dot(i, mX[base], mY[base], mZ[base]) < baselineCone) {
                base = i;
            } else if (mTime[i] - mTime[base] >= baselineDuration) {
                // A pose already in place is not a starting pose for a second turn.
                if (dot(base, mHeldX, mHeldY, mHeldZ) > minimumDot) {
                    return -1;
                }
                return directedTurn(base, i + 1, held, earliest, radialLimit);
            }
        }
        return -1;
    }

    private int directedTurn(int base, int from, int held, long earliest, float radialLimit) {
        final float bx = mX[base];
        final float by = mY[base];
        final float bz = mZ[base];
        final float dx = mHeldX - bx;
        final float dy = mHeldY - by;
        final float dz = mHeldZ - bz;
        final float chordSquared = dx * dx + dy * dy + dz * dz;
        final float crossLimit = 0.17f + chordSquared * 0.27f;
        int first = -1;
        int interior = 0;
        float furthest = 0;
        for (int i = from; i <= held; i++) {
            final float x = mX[i] - bx;
            final float y = mY[i] - by;
            final float z = mZ[i] - bz;
            final float progress = (x * dx + y * dy + z * dz) / chordSquared;
            final float crossSquared = Math.max(0,
                    x * x + y * y + z * z - progress * progress * chordSquared);
            if (Math.abs(mMagnitude[i] - G) > radialLimit
                    || crossSquared > crossLimit * crossLimit || furthest - progress > 0.23f) {
                return -1;
            }
            if (first < 0 && progress >= 0.12f) {
                first = i;
                if (mTime[i] < earliest) {
                    return -1;
                }
            }
            if (progress >= 0.15f && progress <= 0.85f) {
                interior++;
            }
            // Use the real sample interval so an isolated angular jump is not a smooth turn.
            final float maxStep = 0.11f + 4.0e-9f * (mTime[i] - mTime[i - 1]);
            if (2f - 2f * dot(i, mX[i - 1], mY[i - 1], mZ[i - 1]) > maxStep * maxStep) {
                return -1;
            }
            furthest = Math.max(furthest, progress);
        }
        return first >= 0 && interior >= 3 && mTime[held] - mTime[first] >= ms(70)
                ? first : -1;
    }

    /** A pocket exit needs a sustained pull close to both the rotation and the uncover event. */
    int withdrawal(int turn, int held, long far, float acceleration) {
        if (far < mTime[turn] - ms(200) || far > mTime[held] + ms(140)) {
            return -1;
        }
        final long firstNs = Math.max(far - ms(200), mTime[turn] - ms(200));
        final long lastNs = Math.min(far + ms(170), mTime[held]);
        final float threshold = Math.max(0.75f, acceleration * 0.8f);
        int start = -1;
        for (int i = 0; i <= held && mTime[i] <= lastNs; i++) {
            if (mTime[i] < firstNs) {
                continue;
            }
            if (mMagnitude[i] - G < threshold) {
                start = -1;
            } else if (start < 0) {
                start = i;
            } else if (mTime[i] - mTime[start] >= ms(45)) {
                return Math.min(start, turn);
            }
        }
        return -1;
    }

    /** Finds a 3D drive/brake pair around a common pre- and post-motion gravity vector. */
    int translation(int held, long earliest, float acceleration, long settleNs) {
        float gravityX = 0;
        float gravityY = 0;
        float gravityZ = 0;
        for (int i = held; i < mCount; i++) {
            gravityX += mX[i] * mMagnitude[i];
            gravityY += mY[i] * mMagnitude[i];
            gravityZ += mZ[i] * mMagnitude[i];
        }
        final int count = mCount - held;
        gravityX /= count;
        gravityY /= count;
        gravityZ /= count;
        final float gravitySquared = squaredLength(gravityX, gravityY, gravityZ);
        final int drive = translationOnset(earliest, acceleration * acceleration,
                gravityX, gravityY, gravityZ);
        if (drive < 0) {
            return -1;
        }

        // Learn the travel axis from the start of the drive, not from the gravity direction.
        float axisX = 0;
        float axisY = 0;
        float axisZ = 0;
        for (int i = drive; i < mCount && mTime[i] - mTime[drive] <= ms(45); i++) {
            axisX += mX[i] * mMagnitude[i] - gravityX;
            axisY += mY[i] * mMagnitude[i] - gravityY;
            axisZ += mZ[i] * mMagnitude[i] - gravityZ;
        }
        final float axisLength = (float) Math.sqrt(squaredLength(axisX, axisY, axisZ));
        if (axisLength < acceleration) {
            return -1;
        }
        axisX /= axisLength;
        axisY /= axisLength;
        axisZ /= axisLength;

        int driveEnd = -1;
        int brakeEnd = -1;
        int quietStart = -1;
        float driveImpulse = 0;
        float vectorEnergy = 0;
        float axisEnergy = 0;
        float magnitudeChange = 0;
        float netX = 0;
        float netY = 0;
        float netZ = 0;
        int lobe = drive;
        int activeSign = 0;
        float impulse = 0;
        float previousProjection = 0;
        final float threshold = acceleration * 0.85f;
        for (int i = drive; i < mCount; i++) {
            final float x = mX[i] * mMagnitude[i] - gravityX;
            final float y = mY[i] * mMagnitude[i] - gravityY;
            final float z = mZ[i] * mMagnitude[i] - gravityZ;
            final float squared = squaredLength(x, y, z);
            if (squared > TRANSLATION_SHOCK_SQUARED) {
                return -1;
            }
            final float projection = x * axisX + y * axisY + z * axisZ;
            final float dt = i == drive ? 0 : (mTime[i] - mTime[i - 1]) * 1e-9f;
            if (squared > TRANSLATION_QUIET_SQUARED) {
                quietStart = -1;
                vectorEnergy += squared * dt;
                axisEnergy += projection * projection * dt;
                magnitudeChange += Math.abs(mMagnitude[i] * mMagnitude[i] - gravitySquared) * dt;
                netX += x * dt;
                netY += y * dt;
                netZ += z * dt;
            } else if (quietStart < 0) {
                quietStart = i;
            }

            final int sign = projection >= threshold ? 1 : projection <= -threshold ? -1 : 0;
            if (sign == activeSign) {
                if (sign != 0) {
                    impulse += sign * (projection + previousProjection) * 0.5f * dt;
                }
            } else {
                if (activeSign != 0 && i - lobe >= 3 && mTime[i - 1] - mTime[lobe] >= ms(55)
                        && impulse >= acceleration * 0.060f) {
                    if (activeSign > 0 && lobe == drive && driveEnd < 0) {
                        driveEnd = i - 1;
                        driveImpulse = impulse;
                    } else if (activeSign < 0 && driveEnd >= 0 && brakeEnd < 0
                            && mTime[lobe] - mTime[driveEnd] <= ms(130)
                            && impulse >= 0.5f * driveImpulse && impulse <= 2f * driveImpulse) {
                        brakeEnd = i - 1;
                    } else {
                        return -1;
                    }
                }
                lobe = i;
                activeSign = sign;
                impulse = 0;
            }
            previousProjection = projection;
        }
        final float netLimit = driveImpulse * 0.6f;
        return brakeEnd >= 0 && quietStart > brakeEnd && latestNs() - mTime[quietStart] >= settleNs
                && mTime[brakeEnd] - mTime[drive] >= ms(145)
                && mTime[brakeEnd] - mTime[drive] <= ms(600)
                && axisEnergy >= 0.75f * vectorEnergy
                // A returning pose oscillation traces the gravity sphere, not a translation.
                && magnitudeChange >= 0.25f * vectorEnergy
                && squaredLength(netX, netY, netZ) <= netLimit * netLimit ? drive : -1;
    }

    private int translationOnset(long earliest, float thresholdSquared, float gravityX,
            float gravityY, float gravityZ) {
        int quietStart = -1;
        int lastQuiet = -1;
        int stable = -1;
        for (int i = 0; i < mCount; i++) {
            if (mTime[i] < earliest - ms(160)) {
                continue;
            }
            final float squared = squaredLength(mX[i] * mMagnitude[i] - gravityX,
                    mY[i] * mMagnitude[i] - gravityY, mZ[i] * mMagnitude[i] - gravityZ);
            if (squared <= TRANSLATION_QUIET_SQUARED) {
                if (lastQuiet < 0 || lastQuiet != i - 1) {
                    quietStart = i;
                }
                lastQuiet = i;
                if (mTime[i] - mTime[quietStart] >= ms(60)) {
                    stable = i;
                }
            } else if (squared >= thresholdSquared) {
                return squared <= TRANSLATION_SHOCK_SQUARED && mTime[i] >= earliest
                        && stable >= 0 && mTime[i] - mTime[stable] <= ms(100) ? i : -1;
            }
        }
        return -1;
    }

    private static float squaredLength(float x, float y, float z) {
        return x * x + y * y + z * z;
    }

    private float dot(int index, float x, float y, float z) {
        return mX[index] * x + mY[index] * y + mZ[index] * z;
    }

    static long ms(long milliseconds) {
        return milliseconds * 1_000_000L;
    }
}
