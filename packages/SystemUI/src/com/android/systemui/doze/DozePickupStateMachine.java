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

/** Tracks candidate windows and prevents a confirmed motion epoch from being consumed twice. */
final class DozePickupStateMachine {
    enum State { DISABLED, ARMED, EVALUATING, TRIGGERED, REFRACTORY }

    static final long EVALUATION_NS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final long COOLDOWN_NS = TimeUnit.MILLISECONDS.toNanos(1200);
    private static final long EPOCH_GAP_NS = TimeUnit.MILLISECONDS.toNanos(160);

    private State mState = State.DISABLED;
    private long mCandidateNs;
    private long mDeadlineNs;
    private long mConsumedEndNs;
    private long mCooldownUntilNs;
    private long mListenStartNs;

    State state() {
        return mState;
    }

    void setListening(boolean listening, long nowNs) {
        if (!listening) {
            mState = State.DISABLED;
        } else if (mState == State.DISABLED) {
            mListenStartNs = nowNs;
            mCandidateNs = 0;
            mState = idleState();
        }
    }

    boolean begin(long eventNs, long receivedNs, long nowNs) {
        if ((mState != State.ARMED && mState != State.REFRACTORY)
                || eventNs <= Math.max(mCandidateNs, evidenceSinceNs())
                || eventNs < mListenStartNs || eventNs > receivedNs || receivedNs > nowNs
                || receivedNs - eventNs > TimeUnit.MILLISECONDS.toNanos(DozePickupMotion.WINDOW_MS)
                || nowNs - receivedNs >= EVALUATION_NS
                || receivedNs < mCooldownUntilNs) {
            return false;
        }
        mCandidateNs = eventNs;
        mDeadlineNs = receivedNs + EVALUATION_NS;
        // In REFRACTORY this is only a probe; a fresh candidate is not proof of a new gesture.
        mState = State.EVALUATING;
        return true;
    }

    long deadlineNs() {
        return mDeadlineNs;
    }

    long evidenceSinceNs() {
        return mConsumedEndNs == 0 ? 0 : mConsumedEndNs + EPOCH_GAP_NS;
    }

    boolean confirm(long startNs, long endNs, long nowNs) {
        if (mState != State.EVALUATING || startNs <= evidenceSinceNs() || endNs < startNs
                || endNs > nowNs || nowNs >= mDeadlineNs) {
            return false;
        }
        return consume(endNs, nowNs);
    }

    boolean confirmFromHistory(long startNs, long endNs, long nowNs) {
        if ((mState != State.ARMED && mState != State.REFRACTORY)
                || startNs < mListenStartNs || startNs <= evidenceSinceNs()
                || endNs < startNs || endNs > nowNs || nowNs < mCooldownUntilNs) {
            return false;
        }
        return consume(endNs, nowNs);
    }

    private boolean consume(long endNs, long nowNs) {
        mConsumedEndNs = endNs;
        mCooldownUntilNs = nowNs + COOLDOWN_NS;
        mState = State.TRIGGERED;
        return true;
    }

    void finish() {
        if (mState == State.EVALUATING || mState == State.TRIGGERED) {
            mState = idleState();
        }
    }

    private State idleState() {
        return mConsumedEndNs == 0 ? State.ARMED : State.REFRACTORY;
    }
}
