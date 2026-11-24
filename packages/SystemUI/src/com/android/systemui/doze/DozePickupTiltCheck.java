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
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.util.wakelock.WakeLock;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Confirms a pickup with a short, unbatched accelerometer window on the main thread. */
final class DozePickupTiltCheck {
    private static final String WAKE_LOCK_TAG = "DozePickupTiltCheck";
    private static final int SAMPLE_PERIOD_US = 20_000;
    private static final long COOLDOWN_NS = TimeUnit.MILLISECONDS.toNanos(1_200);

    private final SensorManager mSensorManager;
    private final Sensor mAccelerometer;
    private final DelayableExecutor mMainExecutor;
    private final SystemClock mClock;
    private final WakeLock mWakeLock;
    private SampleWindow mWindow;
    private long mCooldownUntilNs;

    DozePickupTiltCheck(SensorManager sensorManager, DelayableExecutor mainExecutor,
            WakeLock wakeLock, SystemClock clock) {
        mSensorManager = sensorManager;
        mAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mMainExecutor = mainExecutor;
        mClock = clock;
        mWakeLock = wakeLock;
    }

    void check(Consumer<Boolean> callback) {
        if (mWindow != null) {
            return;
        }
        long now = mClock.elapsedRealtimeNanos();
        if (now < mCooldownUntilNs) {
            callback.accept(false);
            return;
        }
        if (mAccelerometer == null) {
            callback.accept(false);
            return;
        }
        SampleWindow window = new SampleWindow(callback, now);
        mWindow = window;
        mWakeLock.acquire(WAKE_LOCK_TAG);
        window.mCancelTimeout = mMainExecutor.executeDelayed(
                () -> window.complete(window.mMotion.isConfirmed()), DozePickupMotion.WINDOW_MS);
        if (!mSensorManager.registerListener(window, mAccelerometer, SAMPLE_PERIOD_US)) {
            window.complete(false);
        }
    }

    void cancel() {
        SampleWindow window = mWindow;
        if (window != null) {
            mWindow = null;
            window.mCancelTimeout.run();
            mSensorManager.unregisterListener(window, mAccelerometer);
            mWakeLock.release(WAKE_LOCK_TAG);
        }
    }

    private final class SampleWindow implements SensorEventListener {
        private final Consumer<Boolean> mCallback;
        private final DozePickupMotion mMotion;
        private Runnable mCancelTimeout;

        SampleWindow(Consumer<Boolean> callback, long startNs) {
            mCallback = callback;
            mMotion = new DozePickupMotion(startNs);
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mWindow != this) {
                return;
            }
            mMotion.addSample(event.timestamp, event.values[0], event.values[1], event.values[2]);
            if (mMotion.isRecurrent()) {
                complete(false);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        void complete(boolean confirmed) {
            if (mWindow == this) {
                // Do not restart sampling on each step of a recurring motion trigger.
                if (confirmed || mMotion.isRecurrent()) {
                    mCooldownUntilNs = mClock.elapsedRealtimeNanos() + COOLDOWN_NS;
                }
                cancel();
                mCallback.accept(confirmed);
            }
        }
    }
}
