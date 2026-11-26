/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.os.RemoteException;

import java.util.function.LongSupplier;

/** Tracks the worker's aggregate timed vote in libperfmgr. */
final class AxPowerVote {
    private static final int NONE = AxKernelManager.NONE;
    private static final String[] HINTS = {null, "AX_DRAGONITE_LIGHT", "AX_DRAGONITE_HEAVY"};

    interface Boost {
        void setBoost(String hint, int durationMs) throws RemoteException;
    }

    private final LongSupplier mUptime;
    private int mLevel = NONE;
    private long mUntil;

    AxPowerVote(LongSupplier uptime) {
        mUptime = uptime;
    }

    int level() {
        return mLevel;
    }

    long until() {
        return mUntil;
    }

    String hint() {
        return HINTS[mLevel];
    }

    void apply(Boost boost, int level, long until, boolean reconnect) throws RemoteException {
        long now = mUptime.getAsLong();
        if (level != NONE && until <= now) level = NONE;
        if (!reconnect && level == mLevel && (level == NONE || until == mUntil)) return;

        if (level != NONE && level == mLevel && until < mUntil && mUntil > now) {
            // Repeating a hint can only extend its libperfmgr RequestGroup deadline.
            stop(boost);
            now = mUptime.getAsLong();
            if (until <= now) return;
        }
        if (level != NONE) boost.setBoost(HINTS[level], (int) (until - now));
        // Acquire a different hint before dropping the old one to keep shared floors voted.
        if (mLevel != NONE && mLevel != level) stop(boost);
        mLevel = level;
        mUntil = level == NONE ? 0 : until;
    }

    private void stop(Boost boost) throws RemoteException {
        boost.setBoost(HINTS[mLevel], -1);
        mLevel = NONE;
        mUntil = 0;
    }
}
