/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
 * See the License for the specific language governing permissions and limitations under the
 * License.
 */
package com.android.server.am;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.util.ArrayMap;

import org.junit.Test;

import java.util.Map;

public class AxMemoryManagerTest {
    @Test
    public void snapshotRanksCopiesNonemptyArrayMapAndRejectsMutation() {
        final ArrayMap<String, Integer> ranks = new ArrayMap<>();
        ranks.put("first", 201);
        ranks.put("second", 401);

        final Map<String, Integer> snapshot = AxMemoryManager.snapshotRanks(ranks);
        ranks.clear();
        ranks.put("first", 801);

        assertEquals(Map.of("first", 201, "second", 401), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("third", 801));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.entrySet().iterator().next().setValue(801));
    }
}
