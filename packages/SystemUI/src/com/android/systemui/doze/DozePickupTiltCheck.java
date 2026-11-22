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
import com.android.systemui.util.wakelock.WakeLock;

import java.util.function.Consumer;

/** Confirms a pickup with a short, unbatched accelerometer sample on the main thread. */
final class DozePickupTiltCheck {
    private static final String WAKE_LOCK_TAG = "DozePickupTiltCheck";
    private static final long TIMEOUT_MS = 250;
    private static final int SAMPLE_PERIOD_US = 20_000;
    private static final float MIN_MAGNITUDE_SQUARED = 7 * 7;
    private static final float MAX_MAGNITUDE_SQUARED = 13 * 13;
    // sin²(20°): X/Y are in the display plane, Z is normal to it.
    private static final float MIN_IN_PLANE_FRACTION_SQUARED = 0.117f;

    private final SensorManager mSensorManager;
    private final Sensor mAccelerometer;
    private final DelayableExecutor mMainExecutor;
    private final WakeLock mWakeLock;
    private SampleWindow mWindow;

    DozePickupTiltCheck(SensorManager sensorManager, DelayableExecutor mainExecutor,
            WakeLock wakeLock) {
        mSensorManager = sensorManager;
        mAccelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mMainExecutor = mainExecutor;
        mWakeLock = wakeLock;
    }

    void check(Consumer<Boolean> callback) {
        if (mWindow != null) {
            return;
        }
        if (mAccelerometer == null) {
            callback.accept(false);
            return;
        }
        SampleWindow window = new SampleWindow(callback);
        mWindow = window;
        mWakeLock.acquire(WAKE_LOCK_TAG);
        window.mCancelTimeout = mMainExecutor.executeDelayed(() -> window.complete(false),
                TIMEOUT_MS);
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
        private Runnable mCancelTimeout;
        private boolean mPreviousSampleTilted;

        SampleWindow(Consumer<Boolean> callback) {
            mCallback = callback;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mWindow != this) {
                return;
            }
            float x = event.values[0];
            float y = event.values[1];
            float z = event.values[2];
            float inPlane = x * x + y * y;
            float magnitude = inPlane + z * z;
            boolean tilted = magnitude >= MIN_MAGNITUDE_SQUARED
                    && magnitude <= MAX_MAGNITUDE_SQUARED
                    && inPlane >= magnitude * MIN_IN_PLANE_FRACTION_SQUARED;
            if (tilted && mPreviousSampleTilted) {
                complete(true);
            }
            mPreviousSampleTilted = tilted;
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        void complete(boolean tilted) {
            if (mWindow == this) {
                cancel();
                mCallback.accept(tilted);
            }
        }
    }
}
