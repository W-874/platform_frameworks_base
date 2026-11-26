/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.util.SparseArray;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;

public final class AxKernelMetricsTest {
    @Test
    public void parsesProcStatWithoutDoubleCountingGuestTime() throws Exception {
        final SparseArray<AxKernelMetrics.CpuTimes> times = new SparseArray<>();
        try (BufferedReader reader = new BufferedReader(new StringReader("""
                cpu  40 2 10 90 5 1 2 0 15 1
                cpu0 10 0 3 25 2 0 1 0 4 0
                cpu1 malformed row fields present
                cpu4 30 2 7 65 3 1 1 0 11 1
                intr 100
                """))) {
            AxKernelMetrics.readCpuTimes(reader, times);
        }
        assertEquals(new AxKernelMetrics.CpuTimes(55, 150), times.get(-1));
        assertEquals(new AxKernelMetrics.CpuTimes(14, 41),
                AxKernelMetrics.sumCpuTimes("0 1 2 3", times));
        assertEquals(new AxKernelMetrics.CpuTimes(41, 109),
                AxKernelMetrics.sumCpuTimes("4\t5 6 7", times));
        assertNull(AxKernelMetrics.sumCpuTimes("2 3", times));
        assertNull(AxKernelMetrics.sumCpuTimes("unavailable", times));
    }
}
