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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AxMemoryPolicyTest {
    @Test
    public void foregroundServiceRetentionRequiresPlatformProcess() {
        assertEquals(0, AxMemoryPolicy.foregroundServiceAdj(
                "com.android.systemui", "com.android.systemui", true, 200));
        assertEquals(100, AxMemoryPolicy.foregroundServiceAdj(
                "com.android.launcher3", "com.android.launcher3", true, 200));
        assertEquals(200, AxMemoryPolicy.foregroundServiceAdj(
                "com.android.systemui", "com.android.systemui", false, 200));
        assertEquals(200, AxMemoryPolicy.foregroundServiceAdj(
                "com.android.launcher3", "com.android.launcher3:remote", true, 200));
        assertEquals(25, AxMemoryPolicy.foregroundServiceAdj(
                "com.android.launcher3", "com.android.launcher3", true, 25));
    }

    @Test
    public void retentionCannotDemoteAndOnlyAffectsEligibleCachedProcesses() {
        assertEquals(25, AxMemoryPolicy.cachedAdj(25, false, true, false, true, 201));
        assertEquals(801, AxMemoryPolicy.cachedAdj(999, true, false, false, false, -1));
        assertEquals(999, AxMemoryPolicy.cachedAdj(999, false, false, false, false, 201));
        assertEquals(999, AxMemoryPolicy.cachedAdj(999, false, true, true, true, 201));
        assertEquals(401, AxMemoryPolicy.cachedAdj(999, false, true, false, false, 401));
        assertEquals(201, AxMemoryPolicy.cachedAdj(999, false, true, false, true, -1));
    }

    @Test
    public void repeatedLowMemoryExitsOnlyPromoteSmallWorkingSets() {
        assertEquals(801, AxMemoryPolicy.usageAdj(0, 64_000));
        assertEquals(401, AxMemoryPolicy.usageAdj(1, 64_000));
        assertEquals(201, AxMemoryPolicy.usageAdj(2, 64_000));
        assertEquals(401, AxMemoryPolicy.usageAdj(2, 204_801));
        assertEquals(801, AxMemoryPolicy.usageAdj(2, 512_001));
    }

    @Test
    public void ramTiersAndWarmupBudgetMatchSagitLimits() {
        final long fourGb = 4L << 30;
        assertEquals(10, AxMemoryPolicy.cameraCount(fourGb, true));
        assertEquals(3, AxMemoryPolicy.cameraCount(fourGb, false));
        assertEquals(8, AxMemoryPolicy.screenOnCount(fourGb));
        assertEquals(20, AxMemoryPolicy.cameraCount(fourGb + 1, true));
        assertEquals(5, AxMemoryPolicy.cameraCount(12L << 30, true));
        assertEquals(262_144, AxMemoryPolicy.preforkBudgetKb(fourGb));
        assertTrue(AxMemoryPolicy.preforkBudgetKb(fourGb)
                < 2 * AxMemoryPolicy.UNKNOWN_PSS_KB);
    }

    @Test
    public void explicitReleaseClampsCallerAndRanksBothDimensions() {
        assertEquals(606, AxMemoryPolicy.releaseMinAdj(0));
        assertEquals(606, AxMemoryPolicy.releaseMinAdj(200));
        assertEquals(999, AxMemoryPolicy.releaseMinAdj(1100));
        assertEquals(60, AxMemoryPolicy.releaseCount(-1));
        assertEquals(100, AxMemoryPolicy.releaseCount(106));
        assertEquals(2, AxMemoryPolicy.releaseCount(2));
        assertTrue(AxMemoryPolicy.releaseScore(2, 1) > AxMemoryPolicy.releaseScore(1, 2));
        assertTrue(AxMemoryPolicy.releaseScore(1, 3) > AxMemoryPolicy.releaseScore(2, 1));
    }
}
