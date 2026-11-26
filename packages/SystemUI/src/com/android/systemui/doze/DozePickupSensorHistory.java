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

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;

import com.android.systemui.util.time.SystemClock;

import java.io.PrintWriter;

/** Main-thread owner of the non-wake-up FIFO listener and merged accelerometer stream. */
final class DozePickupSensorHistory {
    private static final int MAX_BATCH_US = 3_000_000;
    private static final int BASELINE_US = 320_000;
    private static final long BASELINE_NS = BASELINE_US * 1000L;
    private static final long MAX_GAP_NS = 100_000_000L;

    private final SensorManager mSensors;
    private final SystemClock mClock;
    private final Sensor mAccelerometer;
    private final Sensor mHistorySensor;
    private final int mPeriodUs;
    private final int mBatchUs;
    private final Runnable mOnFlushCompleted;
    private final DozePickupMotionFeatures.Samples mSamples = new DozePickupMotionFeatures.Samples();

    private BatchListener mBatchListener;
    private long mStartedNs;
    private boolean mFlushPending;
    private int mRegistrationFailures;
    private int mFlushRequests;
    private int mFlushCompletions;
    private int mFlushFailures;

    DozePickupSensorHistory(SensorManager sensors, SystemClock clock, int historyHz, int batchMs,
            Runnable onFlushCompleted) {
        mSensors = sensors;
        mClock = clock;
        mOnFlushCompleted = onFlushCompleted;
        mHistorySensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, false);
        mAccelerometer = mHistorySensor != null ? mHistorySensor
                : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mPeriodUs = historyHz > 0 && mHistorySensor != null
                ? Math.max(1_000_000 / Math.min(historyHz, 50), mHistorySensor.getMinDelay()) : 0;
        int capacity = mHistorySensor == null ? 0 : mHistorySensor.getFifoMaxEventCount();
        int reserved = mHistorySensor == null ? 0 : mHistorySensor.getFifoReservedEventCount();
        if (mPeriodUs == 0 || mPeriodUs * 1000L > MAX_GAP_NS || batchMs <= 0 || capacity <= 1
                || (capacity - 1L) * mPeriodUs < BASELINE_US) {
            mBatchUs = 0;
        } else {
            // Prefer guaranteed FIFO capacity. Shared FIFO is opportunistic; coverage is checked
            // from actual event timestamps even on sensors that advertise reserved space.
            if ((reserved - 1L) * mPeriodUs >= BASELINE_US) {
                capacity = Math.min(capacity, reserved);
            }
            mBatchUs = (int) Math.min(Math.min(batchMs * 1000L, MAX_BATCH_US),
                    (capacity - 1L) * mPeriodUs);
        }
    }

    Sensor accelerometer() {
        return mAccelerometer;
    }

    DozePickupMotionFeatures.Samples samples() {
        return mSamples;
    }

    void start(long nowNs) {
        mStartedNs = nowNs;
        if (mBatchUs == 0) {
            return;
        }
        BatchListener listener = new BatchListener();
        if (mSensors.registerListener(listener, mHistorySensor, mPeriodUs, mBatchUs)) {
            mBatchListener = listener;
        } else {
            mRegistrationFailures++;
        }
    }

    void stop() {
        BatchListener listener = mBatchListener;
        mBatchListener = null;
        mFlushPending = false;
        mSamples.clear();
        if (listener != null) {
            mSensors.unregisterListener(listener, mHistorySensor);
        }
    }

    boolean isRegistered() {
        return mBatchListener != null;
    }

    boolean flushPending() {
        return mFlushPending;
    }

    void requestFlush() {
        if (mBatchListener == null || mFlushPending) {
            return;
        }
        mFlushRequests++;
        // This queues a flush, it does not synchronously deliver the FIFO contents.
        mFlushPending = mSensors.flush(mBatchListener);
        if (!mFlushPending) {
            mFlushFailures++;
        }
    }

    void add(SensorEvent event, long nowNs) {
        long ns = event.timestamp;
        if (ns > 0 && ns >= mStartedNs && ns <= nowNs) {
            mSamples.add(ns, event.values[0], event.values[1], event.values[2]);
        }
    }

    boolean hasPretriggerCoverage(long eventNs) {
        long nextNs = eventNs;
        for (int i = mSamples.size() - 1; i >= 0; i--) {
            long ns = mSamples.timeAt(i);
            if (ns > eventNs) {
                continue;
            }
            if (nextNs - ns > MAX_GAP_NS) {
                return false;
            }
            if (eventNs - ns >= BASELINE_NS) {
                return true;
            }
            nextNs = ns;
        }
        return false;
    }

    void dump(PrintWriter pw) {
        pw.println("    historyRegistered=" + isRegistered() + " periodUs=" + mPeriodUs
                + " requestedBatchUs=" + mBatchUs + " fifoMax="
                + (mHistorySensor == null ? 0 : mHistorySensor.getFifoMaxEventCount())
                + " fifoReserved="
                + (mHistorySensor == null ? 0 : mHistorySensor.getFifoReservedEventCount())
                + " registrationFailures=" + mRegistrationFailures);
        pw.println("    bufferedSamples=" + mSamples.size() + " lastSampleNs=" + mSamples.latestNs()
                + " flushPending=" + mFlushPending + " flushRequests=" + mFlushRequests
                + " flushCompletions=" + mFlushCompletions + " flushFailures=" + mFlushFailures);
    }

    private final class BatchListener implements SensorEventListener2 {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mBatchListener == this) {
                add(event, mClock.elapsedRealtimeNanos());
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        @Override
        public void onFlushCompleted(Sensor sensor) {
            if (mBatchListener == this && mFlushPending) {
                mFlushPending = false;
                mFlushCompletions++;
                mOnFlushCompleted.run();
            }
        }
    }
}
