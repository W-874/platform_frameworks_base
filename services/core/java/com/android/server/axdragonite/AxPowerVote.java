/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.os.RemoteException;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Tracks one axis of the worker's aggregate timed vote in libperfmgr. */
final class AxPowerVote {
    interface Boost {
        void setBoost(String hint, int durationMs) throws RemoteException;
    }

    private final LongSupplier mUptime;
    private String mHint;
    private long mUntil;

    AxPowerVote(LongSupplier uptime) {
        mUptime = uptime;
    }

    long until() {
        return mUntil;
    }

    String hint() {
        return mHint;
    }

    void apply(Boost boost, String hint, long until, boolean reconnect) throws RemoteException {
        long now = mUptime.getAsLong();
        if (hint != null && until <= now) hint = null;
        boolean sameHint = Objects.equals(hint, mHint);
        if (!reconnect && sameHint && (hint == null || until == mUntil)) return;

        if (hint != null && sameHint && until < mUntil && mUntil > now) {
            // Repeating a hint can only extend its libperfmgr RequestGroup deadline.
            stop(boost);
            now = mUptime.getAsLong();
            if (until <= now) return;
        }
        if (hint != null) boost.setBoost(hint, (int) (until - now));
        // Acquire a different hint before dropping the old one to keep shared floors voted.
        if (mHint != null && !sameHint) stop(boost);
        mHint = hint;
        mUntil = hint == null ? 0 : until;
    }

    private void stop(Boost boost) throws RemoteException {
        boost.setBoost(mHint, -1);
        mHint = null;
        mUntil = 0;
    }
}
