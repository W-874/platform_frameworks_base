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
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;

import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.util.wakelock.WakeLock;

import java.io.PrintWriter;
import java.util.concurrent.TimeUnit;

/** Confirms hardware pickup candidates with observed motion and proximity on the main thread. */
final class DozePickupTiltCheck {
    private static final String WAKE_LOCK_TAG = "DozePickupTiltCheck";
    private static final int LIVE_PERIOD_US = 20_000;
    private static final long FLUSH_GRACE_NS = TimeUnit.MILLISECONDS.toNanos(60);
    private static final long NS_PER_MS = TimeUnit.MILLISECONDS.toNanos(1);

    private final SensorManager mSensors;
    private final DelayableExecutor mExecutor;
    private final WakeLock mWakeLock;
    private final SystemClock mClock;
    private final Runnable mOnConfirmed;
    private final DozePickupSensorHistory mHistory;
    private final DozePickupMotion mMotion;
    private final DozePickupStateMachine mState = new DozePickupStateMachine();
    private final Sensor mSignificantMotion;
    private final Sensor mNonWakeProximity;
    private final Sensor mDefaultProximity;

    private Window mWindow;
    private ProximityListener mProximity;
    private SignificantMotionListener mFallback;
    private boolean mNear;
    private long mProximityNs;
    private long mNearToFarNs;
    private int mPickupCandidates;
    private int mFallbackCandidates;
    private int mEvaluations;
    private int mEvaluationsWithHistory;
    private int mExpired;
    private int mLiveRegistrationFailures;
    private int mFallbackRegistrationFailures;
    private int mConfirmed;
    private long mLastConfirmedLatencyNs;
    private long mMaxConfirmedLatencyNs;
    private DozePickupMotion.Path mLastPath = DozePickupMotion.Path.NONE;

    DozePickupTiltCheck(SensorManager sensors, DelayableExecutor executor, WakeLock wakeLock,
            SystemClock clock, int historyHz, int batchMs, float tiltDegrees, float acceleration,
            Runnable onConfirmed) {
        mSensors = sensors;
        mExecutor = executor;
        mWakeLock = wakeLock;
        mClock = clock;
        mOnConfirmed = onConfirmed;
        mHistory = new DozePickupSensorHistory(sensors, clock, historyHz, batchMs,
                this::onHistoryFlushed);
        mMotion = new DozePickupMotion(tiltDegrees, acceleration);
        mSignificantMotion = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION, true);
        mNonWakeProximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY, false);
        mDefaultProximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
    }

    void setListening(boolean listening) {
        if (listening == isListening()) {
            return;
        }
        long nowNs = mClock.elapsedRealtimeNanos();
        mState.setListening(listening, nowNs);
        if (listening) {
            if (mHistory.accelerometer() == null) {
                return;
            }
            mProximity = registerProximity(mNonWakeProximity);
            if (mProximity == null && mDefaultProximity != mNonWakeProximity) {
                mProximity = registerProximity(mDefaultProximity);
            }
            mHistory.start(nowNs);
            setFallback(!mHistory.isRegistered());
            return;
        }

        if (mWindow != null) {
            finish(mWindow);
        }
        mHistory.stop();
        setFallback(false);
        ProximityListener proximity = mProximity;
        mProximity = null;
        mNear = false;
        mProximityNs = 0;
        mNearToFarNs = 0;
        if (proximity != null) {
            mSensors.unregisterListener(proximity, proximity.mSensor);
        }
    }

    void candidate(long eventNs, long receivedNs) {
        mPickupCandidates++;
        begin(eventNs, receivedNs);
    }

    private boolean isListening() {
        return mState.state() != DozePickupStateMachine.State.DISABLED;
    }

    private boolean begin(long eventNs, long receivedNs) {
        Sensor accelerometer = mHistory.accelerometer();
        if (accelerometer == null || !mState.begin(eventNs, receivedNs,
                mClock.elapsedRealtimeNanos())) {
            return false;
        }
        mEvaluations++;
        Window window = new Window(eventNs, receivedNs, mState.deadlineNs());
        mWindow = window;
        boolean started = false;
        try {
            mWakeLock.acquire(WAKE_LOCK_TAG);
            window.mWakeHeld = true;
            long remainingNs = window.mDeadlineNs - mClock.elapsedRealtimeNanos();
            if (remainingNs <= 0) {
                mExpired++;
                return true;
            }
            window.mCancelTimeout = mExecutor.executeDelayed(() -> expire(window),
                    (remainingNs + NS_PER_MS - 1) / NS_PER_MS);
            // Flush first. Keep the history connection alive while the second connection asks
            // for 50 Hz; changing a registration in place cannot preserve its pending FIFO.
            mHistory.requestFlush();
            if (mClock.elapsedRealtimeNanos() >= window.mDeadlineNs) {
                mExpired++;
                return true;
            }
            window.mLiveRegistered = mSensors.registerListener(window, accelerometer,
                    LIVE_PERIOD_US);
            if (!window.mLiveRegistered) {
                mLiveRegistrationFailures++;
                return true;
            }
            started = true;
        } finally {
            if (!started) {
                finish(window);
            }
        }
        evaluate(window, mClock.elapsedRealtimeNanos());
        return true;
    }

    private void expire(Window window) {
        if (mWindow == window) {
            mExpired++;
            finish(window);
        }
    }

    private void onHistoryFlushed() {
        if (mWindow != null) {
            evaluate(mWindow, mClock.elapsedRealtimeNanos());
        }
    }

    private void evaluate(Window window, long nowNs) {
        if (nowNs >= window.mDeadlineNs) {
            expire(window);
            return;
        }
        if (mProximityNs == 0 || mNear
                || (mHistory.flushPending() && nowNs - window.mReceivedNs < FLUSH_GRACE_NS)) {
            return;
        }
        DozePickupMotion.Path path = mMotion.classify(mHistory.samples(), window.mEventNs,
                nowNs, mState.evidenceSinceNs(), mNearToFarNs, mNear);
        if (path != DozePickupMotion.Path.NONE
                && mState.confirm(mMotion.motionStartNs(), mMotion.motionEndNs(),
                        mClock.elapsedRealtimeNanos())) {
            mLastPath = path;
            mConfirmed++;
            mLastConfirmedLatencyNs = mClock.elapsedRealtimeNanos() - window.mReceivedNs;
            mMaxConfirmedLatencyNs = Math.max(mLastConfirmedLatencyNs, mMaxConfirmedLatencyNs);
            try {
                // Dispatch before binder calls to tear down the completed evaluation.
                mOnConfirmed.run();
            } finally {
                finish(window);
            }
        }
    }

    private void finish(Window window) {
        if (mWindow != window) {
            return; // The confirmation callback may have disabled listening synchronously.
        }
        mWindow = null;
        try {
            if (window.mCancelTimeout != null) {
                window.mCancelTimeout.run();
            }
            if (window.mLiveRegistered) {
                mSensors.unregisterListener(window, mHistory.accelerometer());
            }
        } finally {
            if (window.mWakeHeld) {
                mWakeLock.release(WAKE_LOCK_TAG);
            }
            mState.finish();
        }
        if (isListening()) {
            boolean historyAvailable = mHistory.hasPretriggerCoverage(window.mEventNs);
            if (historyAvailable) {
                mEvaluationsWithHistory++;
            }
            setFallback(!historyAvailable);
        }
    }

    private ProximityListener registerProximity(Sensor sensor) {
        if (sensor == null) {
            return null;
        }
        ProximityListener listener = new ProximityListener(sensor);
        return mSensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                ? listener : null;
    }

    private void setFallback(boolean wanted) {
        if (!wanted) {
            SignificantMotionListener listener = mFallback;
            mFallback = null;
            if (listener != null) {
                mSensors.cancelTriggerSensor(listener, mSignificantMotion);
            }
        } else if (mFallback == null && mSignificantMotion != null) {
            // TYPE_SIGNIFICANT_MOTION is an independent, one-shot wake-up hint, not evidence
            // that the device was raised. Never suppress QTI when it cannot be armed.
            SignificantMotionListener listener = new SignificantMotionListener();
            if (mSensors.requestTriggerSensor(listener, mSignificantMotion)) {
                mFallback = listener;
            } else {
                mFallbackRegistrationFailures++;
            }
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  Pickup motion: state=" + mState.state() + " lastPath=" + mLastPath
                + " evidenceSinceNs=" + mState.evidenceSinceNs() + " deadlineNs="
                + (mWindow == null ? 0 : mWindow.mDeadlineNs));
        pw.println("    QTI=" + mPickupCandidates + " significantMotion=" + mFallbackCandidates
                + " fallbackAvailable=" + (mSignificantMotion != null)
                + " fallbackArmed=" + (mFallback != null) + " fallbackRegistrationFailures="
                + mFallbackRegistrationFailures);
        pw.println("    evaluated=" + mEvaluations + " withPretriggerHistory="
                + mEvaluationsWithHistory + " confirmed=" + mConfirmed + " expired=" + mExpired
                + " liveRegistrationFailures=" + mLiveRegistrationFailures);
        pw.println("    lastConfirmedLatencyMs=" + mLastConfirmedLatencyNs / NS_PER_MS
                + " maxConfirmedLatencyMs=" + mMaxConfirmedLatencyNs / NS_PER_MS);
        pw.println("    proximity=" + (mProximity == null ? "unregistered"
                : mProximity.mSensor.isWakeUpSensor() ? "wake" : "nonwake")
                + " proximityNs=" + mProximityNs + " near=" + mNear
                + " nearToFarNs=" + mNearToFarNs);
        mHistory.dump(pw);
    }

    private final class Window implements SensorEventListener {
        private final long mEventNs;
        private final long mReceivedNs;
        private final long mDeadlineNs;
        private Runnable mCancelTimeout;
        private boolean mLiveRegistered;
        private boolean mWakeHeld;

        Window(long eventNs, long receivedNs, long deadlineNs) {
            mEventNs = eventNs;
            mReceivedNs = receivedNs;
            mDeadlineNs = deadlineNs;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mWindow == this) {
                long nowNs = mClock.elapsedRealtimeNanos();
                if (nowNs < mDeadlineNs) {
                    mHistory.add(event, nowNs);
                }
                evaluate(this, nowNs);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    }

    private final class ProximityListener implements SensorEventListener {
        private final Sensor mSensor;

        ProximityListener(Sensor sensor) {
            mSensor = sensor;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            long nowNs = mClock.elapsedRealtimeNanos();
            if (mProximity != this || event.timestamp <= mProximityNs || event.timestamp > nowNs
                    || !Float.isFinite(event.values[0]) || event.values[0] < 0) {
                return;
            }
            // The sagit HAL advertises 5.000305 cm but can report FAR as 5.0 cm.
            boolean near = event.values[0] < Math.min(5f, mSensor.getMaximumRange());
            if (near) {
                mNearToFarNs = 0;
            } else if (mProximityNs != 0 && mNear) {
                mNearToFarNs = event.timestamp;
            }
            mNear = near;
            mProximityNs = event.timestamp;
            if (mWindow != null) {
                evaluate(mWindow, nowNs);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    }

    private final class SignificantMotionListener extends TriggerEventListener {
        @Override
        public void onTrigger(TriggerEvent event) {
            if (mFallback != this) {
                return;
            }
            mFallback = null; // Automatically disabled by SensorManager before this callback.
            mFallbackCandidates++;
            if (!begin(event.timestamp, mClock.elapsedRealtimeNanos())
                    && isListening() && mWindow == null) {
                setFallback(!mHistory.hasPretriggerCoverage(event.timestamp));
            }
        }
    }
}
