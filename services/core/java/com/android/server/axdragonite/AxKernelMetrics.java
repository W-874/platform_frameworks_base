/*
 * Copyright (C) 2026 The Android Open Source Project
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.os.FileUtils;
import android.os.SystemClock;
import android.util.SparseArray;

import com.android.server.stats.pull.psi.PsiData;
import com.android.server.stats.pull.psi.PsiExtractor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.regex.Pattern;

/** On-demand kernel metrics for the privileged Dragonite dump, never a policy input. */
final class AxKernelMetrics {
    private static final File CPUFREQ = new File("/sys/devices/system/cpu/cpufreq");
    private static final Pattern POLICY = Pattern.compile("policy[0-9]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private AxKernelMetrics() {}

    static void dump(PrintWriter pw) {
        pw.println("  kernelMetrics elapsedRealtimeMs=" + SystemClock.elapsedRealtime());
        final SparseArray<CpuTimes> times = new SparseArray<>();
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/stat"))) {
            readCpuTimes(reader, times);
        } catch (IOException ignored) {
            times.clear();
        }
        pw.println("    cpu " + describe(times.get(-1)));
        File[] policies = CPUFREQ.listFiles((dir, name) -> POLICY.matcher(name).matches());
        if (policies == null || policies.length == 0) {
            pw.println("    cpufreq unavailable");
        } else {
            Arrays.sort(policies);
            for (File policy : policies) {
                final String cpus = readNode(policy, "related_cpus");
                pw.println("    " + policy.getName() + " cpus=" + cpus
                        + " governor=" + readNode(policy, "scaling_governor")
                        + " currentKHz=" + readNode(policy, "scaling_cur_freq")
                        + " minKHz=" + readNode(policy, "scaling_min_freq")
                        + " maxKHz=" + readNode(policy, "scaling_max_freq")
                        + ' ' + describe(sumCpuTimes(cpus, times)));
            }
        }
        final PsiData pressure = new PsiExtractor().getPsiData(PsiData.ResourceType.MEMORY);
        if (pressure == null) {
            pw.println("    memoryPsi unavailable");
        } else {
            pw.println("    memoryPsi someAvg10Percent=" + pressure.getSomeAvg10SecPercentage()
                    + " fullAvg10Percent=" + pressure.getFullAvg10SecPercentage()
                    + " someTotalUsec=" + pressure.getSomeTotalUsec()
                    + " fullTotalUsec=" + pressure.getFullTotalUsec());
        }
    }

    private static String readNode(File policy, String name) {
        try {
            final String value = FileUtils.readTextFile(new File(policy, name), 1024, null).trim();
            if (!value.isEmpty()) return value;
        } catch (IOException ignored) {
            // An offline policy or a missing attribute is not a zero-valued metric.
        }
        return "unavailable";
    }

    record CpuTimes(long active, long total) {}

    static void readCpuTimes(BufferedReader reader, SparseArray<CpuTimes> times) throws IOException {
        for (String line; (line = reader.readLine()) != null && line.startsWith("cpu");) {
            final String[] fields = SPACES.split(line);
            if (fields.length < 5) continue;
            try {
                final int id = fields[0].equals("cpu") ? -1
                        : Integer.parseInt(fields[0].substring(3));
                long total = 0;
                long idle = 0;
                // guest and guest_nice are already included in user and nice.
                for (int i = 1; i < Math.min(fields.length, 9); i++) {
                    final long ticks = Long.parseLong(fields[i]);
                    total += ticks;
                    if (i == 4 || i == 5) idle += ticks;
                }
                times.put(id, new CpuTimes(total - idle, total));
            } catch (NumberFormatException ignored) {
                // Continue with the remaining CPUs if any single row is unreadable.
            }
        }
    }

    static CpuTimes sumCpuTimes(String cpus, SparseArray<CpuTimes> times) {
        long active = 0;
        long total = 0;
        boolean available = false;
        try {
            for (String cpu : SPACES.split(cpus)) {
                final CpuTimes cpuTimes = times.get(Integer.parseInt(cpu));
                if (cpuTimes != null) {
                    available = true;
                    active += cpuTimes.active();
                    total += cpuTimes.total();
                }
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return available ? new CpuTimes(active, total) : null;
    }

    private static String describe(CpuTimes times) {
        return times == null ? "ticks=unavailable"
                : "activeTicks=" + times.active() + " totalTicks=" + times.total();
    }
}
