/*
 * Copyright 2025-2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

/** Tracks one admitted game independently of its bounded loading vote. */
final class AxGameSession {
    record Game(String packageName, int uid, int pid, long startTime) {
        boolean matches(String name, int uid, int pid, long startTime) {
            return this.uid == uid && this.pid == pid && this.startTime == startTime
                    && packageName.equals(name);
        }
    }

    private Game mGame;
    private boolean mLoading;
    private long mLoadingUntil;

    Game game() {
        return mGame;
    }

    void clear() {
        mGame = null;
        mLoading = false;
        mLoadingUntil = 0;
    }

    void update(String name, int uid, int pid, long startTime, boolean currentUser, boolean topUid,
            boolean performance) {
        if (!currentUser || !topUid || !performance || startTime < 0) {
            clear();
        } else if (mGame == null || !mGame.matches(name, uid, pid, startTime)) {
            clear();
            mGame = new Game(name, uid, pid, startTime);
        }
    }

    void loading(String name, int uid, int pid, long startTime, boolean active, long until) {
        if (mGame == null || !mGame.matches(name, uid, pid, startTime) || mLoading == active) {
            return;
        }
        mLoading = active;
        mLoadingUntil = active ? until : 0;
    }

    long loadingUntil(long now) {
        return mLoadingUntil > now ? mLoadingUntil : 0;
    }

    static long topAppIsolationUntil(long requestedUntil, boolean gameBig, boolean uiBigInTopApp) {
        return gameBig || uiBigInTopApp ? 0 : requestedUntil;
    }
}
