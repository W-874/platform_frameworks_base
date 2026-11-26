/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package com.android.server.am;

/** Pure policy for the Axion memory hooks; process state and execution remain owned by AMS. */
final class AxMemoryPolicy {
    static final int CACHED_MIN_ADJ = 900;
    static final int PREFORK_ADJ = 801;
    static final int MAX_PREFORKS = 5;
    static final int MAX_RELEASE_COUNT = 100;
    static final long UNKNOWN_PSS_KB = 204_800;
    static final long LMK_WINDOW_MS = 600_000;
    static final long REMOVE_COOLDOWN_MS = 86_400_000;
    static final long PREFORK_LIFETIME_MS = 10_800_000;
    private static final long GIB = 1L << 30;

    static int foregroundServiceAdj(String packageName, String processName, boolean systemApp,
            int adj) {
        if (!systemApp || !packageName.equals(processName)) return adj;
        return switch (packageName) {
            case "com.android.systemui" -> Math.min(adj, 0);
            case "com.android.launcher3", "com.camera.ufs.service" -> Math.min(adj, 100);
            default -> adj;
        };
    }

    static int cachedAdj(int adj, boolean prefork, boolean shownUi, boolean recentRemoval,
            boolean game, int usageAdj) {
        if (adj < CACHED_MIN_ADJ) return adj;
        if (prefork) return PREFORK_ADJ;
        if (!shownUi || recentRemoval) return adj;
        return game ? 201 : usageAdj > 0 ? Math.min(adj, usageAdj) : adj;
    }

    static int usageAdj(int recentLmkCount, long cachedPssKb) {
        if (cachedPssKb > 512_000) return 801;
        if (recentLmkCount == 0) return 801;
        return recentLmkCount == 1 || cachedPssKb > 204_800 ? 401 : 201;
    }

    static int cameraCount(long ram, boolean cold) {
        if (ram > 10 * GIB) return 5;
        if (ram > 6 * GIB) return cold ? 15 : 5;
        if (ram > 4 * GIB) return cold ? 20 : 8;
        return cold ? 10 : 3;
    }

    static int screenOnCount(long ram) {
        if (ram > 10 * GIB) return 10;
        if (ram > 6 * GIB) return 15;
        return ram > 4 * GIB ? 20 : 8;
    }

    static long preforkBudgetKb(long ram) {
        return ram <= 4 * GIB ? 262_144 : Math.min(2_097_152, ram / 8192);
    }

    static int releaseMinAdj(int adj) {
        return adj <= 0 ? 606 : Math.max(606, Math.min(999, adj));
    }

    static int releaseCount(int count) {
        return count <= 0 ? 60 : Math.min(MAX_RELEASE_COUNT, count);
    }

    static int releaseScore(int adjRank, int rssRank) {
        return 3 * adjRank + 2 * rssRank;
    }

    private AxMemoryPolicy() {}
}
