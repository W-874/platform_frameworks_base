/*
 * Copyright 2025-2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.os.Process;
import android.os.SystemClock;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/** Scope a Dragonite affinity request to the main and named render threads of one UI process. */
final class AxUiPlacement {
    private static final String TAG = "AxDragonitePlacement";
    private static final int DISCOVERY_INTERVAL_MS = 200;

    private record Placement(int tid, long startTime, long[] baseline, long selected) {}
    private record FairBoost(Placement thread, int baseline) {}
    private static final int FAIR_PRIORITY = Process.THREAD_PRIORITY_DISPLAY;
    private static final int RT_POLICY = Process.SCHED_RR | Process.SCHED_RESET_ON_FORK;
    private static final long REALTIME_MS = 48;

    private final int mPid;
    private final long mMask;
    private final long mStartTime;
    private boolean mMainAttempted;
    private long mLastDiscovery = -DISCOVERY_INTERVAL_MS;
    private Placement mMain;
    private Placement mRender;
    private boolean mTriedRealtime;
    private long mRealtimeUntil = Long.MAX_VALUE;
    private FairBoost mMainBoost;
    private FairBoost mRenderBoost;

    AxUiPlacement(int pid, long mask) {
        mPid = pid;
        mMask = mask;
        mStartTime = startTime(pid);
    }

    void refresh() {
        if (mStartTime < 0 || mStartTime != startTime(mPid)) return;
        if (!mMainAttempted) {
            mMainAttempted = true;
            mMain = place(mPid);
        }
        if (mRender != null) return;
        long now = SystemClock.uptimeMillis();
        if (now - mLastDiscovery < DISCOVERY_INTERVAL_MS) return;
        mLastDiscovery = now;
        File[] tasks = new File("/proc/" + mPid + "/task").listFiles();
        if (tasks == null) return;
        for (File task : tasks) {
            try {
                int tid = Integer.parseInt(task.getName());
                if (tid == mPid || !"RenderThread".equals(
                        Files.readString(new File(task, "comm").toPath()).trim())) continue;
                mRender = place(tid);
                break;
            } catch (IOException | NumberFormatException ignored) {
                // A thread can exit between listFiles and the read.
            }
        }
    }

    long reconcileRealtime(long now) {
        if (!mTriedRealtime && mMain != null) {
            mTriedRealtime = true;
            if (mMain.startTime() == startTime(mPid)) {
                try {
                    if (Process.getThreadScheduler(mPid) == Process.SCHED_OTHER) {
                        Process.setThreadScheduler(mPid, RT_POLICY, 1);
                        mRealtimeUntil = now + REALTIME_MS;
                    }
                } catch (RuntimeException e) {
                    Slog.d(TAG, "Cannot temporarily promote thread " + mPid, e);
                }
            }
        }
        if (mRealtimeUntil <= now) stopRealtime();
        return mRealtimeUntil;
    }

    void stopRealtime() {
        if (mRealtimeUntil == Long.MAX_VALUE) return;
        mRealtimeUntil = Long.MAX_VALUE;
        if (mMain.startTime() != startTime(mPid)) return;
        try {
            if (Process.getThreadScheduler(mPid) == RT_POLICY) {
                Process.setThreadScheduler(mPid, Process.SCHED_OTHER, 0);
            }
        } catch (RuntimeException e) {
            Slog.d(TAG, "Cannot restore scheduler for thread " + mPid, e);
        }
    }

    void boostFairPriority() {
        if (mMainBoost == null) mMainBoost = boostFairPriority(mMain);
        if (mRenderBoost == null) mRenderBoost = boostFairPriority(mRender);
    }

    private FairBoost boostFairPriority(Placement thread) {
        if (thread == null || thread.startTime() != startTime(thread.tid())) return null;
        try {
            int tid = thread.tid();
            if (Process.getThreadScheduler(tid) != Process.SCHED_OTHER) return null;
            int baseline = Process.getThreadPriority(tid);
            // Do not cross Android's background task-profile boundary or undo an app's boost.
            if (baseline <= FAIR_PRIORITY || baseline >= Process.THREAD_PRIORITY_BACKGROUND) {
                return null;
            }
            Process.setThreadPriority(tid, FAIR_PRIORITY);
            return new FairBoost(thread, baseline);
        } catch (RuntimeException e) {
            Slog.d(TAG, "Cannot raise fair priority for thread " + thread.tid(), e);
            return null;
        }
    }

    void stopFairBoost() {
        restoreFairPriority(mRenderBoost);
        restoreFairPriority(mMainBoost);
        mMainBoost = null;
        mRenderBoost = null;
    }

    private void restoreFairPriority(FairBoost boost) {
        if (boost == null) return;
        Placement thread = boost.thread();
        int tid = thread.tid();
        if (thread.startTime() != startTime(tid)) return;
        try {
            if (Process.getThreadPriority(tid) == FAIR_PRIORITY) {
                Process.setThreadPriority(tid, boost.baseline());
            }
        } catch (RuntimeException e) {
            Slog.d(TAG, "Cannot restore fair priority for thread " + tid, e);
        }
    }

    void restore() {
        stopRealtime();
        stopFairBoost();
        if (mStartTime < 0 || mStartTime != startTime(mPid)) return;
        restore(mRender);
        restore(mMain);
    }

    private Placement place(int tid) {
        long start = startTime(tid);
        if (start < 0) return null;
        try {
            long[] affinity = Process.getSchedAffinity(tid);
            if (affinity.length == 0) return null;
            long selected = affinity[0] & mMask;
            if (selected == 0) return null;
            if (selected == affinity[0] && affinity.length == 1) {
                return new Placement(tid, start, null, selected);
            }
            if (start != startTime(tid)) return null;
            Process.setSchedAffinity(tid, new long[]{selected});
            return new Placement(tid, start, affinity, selected);
        } catch (RuntimeException e) {
            Slog.d(TAG, "Cannot place thread " + tid, e);
            return null;
        }
    }

    private void restore(Placement placement) {
        if (placement == null || placement.baseline() == null
                || placement.startTime() != startTime(placement.tid())) return;
        try {
            long[] affinity = Process.getSchedAffinity(placement.tid());
            if (affinity.length == 0 || (affinity[0] & ~placement.selected()) != 0
                    || Arrays.stream(affinity, 1, affinity.length).anyMatch(bits -> bits != 0)) {
                return;
            }
            Process.setSchedAffinity(placement.tid(), placement.baseline());
        } catch (RuntimeException e) {
            Slog.d(TAG, "Cannot restore thread " + placement.tid(), e);
        }
    }

    private long startTime(int tid) {
        try {
            String stat = Files.readString(new File("/proc/" + mPid + "/task/" + tid + "/stat")
                    .toPath());
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split(" +");
            return fields.length > 19 ? Long.parseLong(fields[19]) : -1;
        } catch (IOException | NumberFormatException e) {
            return -1;
        }
    }
}
