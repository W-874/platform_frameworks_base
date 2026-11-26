/*
 * Copyright 2025-2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

/** SystemServer scene coordinator, adapted from Axion's Dragonite session records. */
public final class AxDragoniteService {
    private static final String TAG = "AxDragoniteService";
    private static final String ENABLED = "ax_dragonite_enabled";
    private static final String AFFINITY = "ax_dragonite_affinity";
    private static final String REALTIME = "ax_dragonite_realtime";
    private static volatile AxDragoniteService sService;

    // Client pulses from one process share a Binder identity. In-process callers use a distinct
    // token per operation so completion of one overlapping transition cannot cancel another.
    private record Key(Object owner, int scene) {}

    private final class Lease implements IBinder.DeathRecipient {
        final Key key;
        final int pid;
        final AxKernelManager.Scene policy;
        long expires;

        Lease(Key key, int pid, AxKernelManager.Scene policy, long expires) {
            this.key = key;
            this.pid = pid;
            this.policy = policy;
            this.expires = expires;
        }

        @Override
        public void binderDied() {
            mWorker.post(() -> {
                int index = mLeases.indexOfKey(key);
                if (index >= 0 && mLeases.valueAt(index) == this) {
                    // Binder already removed this death registration.
                    mLeases.removeAt(index);
                    reconcile();
                }
            });
        }
    }

    private final Context mContext;
    private final Handler mWorker;
    private final ArrayMap<Key, Lease> mLeases = new ArrayMap<>();
    private final SparseIntArray mUiPids = new SparseIntArray();
    private final SparseArray<AxUiPlacement> mPlacements = new SparseArray<>();
    private final Runnable mExpire = this::reconcile;
    private AxKernelManager mKernel;
    private boolean mEnabled;
    private boolean mAffinity;
    private boolean mRealtime;

    public static boolean isConfigured() {
        return AxKernelManager.isConfigured();
    }

    public static void start(Context context) {
        if (sService == null && isConfigured()) sService = new AxDragoniteService(context);
    }

    private AxDragoniteService(Context context) {
        mContext = context;
        HandlerThread thread = new HandlerThread("AxDragoniteWorker", Process.THREAD_PRIORITY_FOREGROUND);
        thread.start();
        mWorker = new Handler(thread.getLooper());
        mWorker.post(() -> {
            try {
                mKernel = AxKernelManager.load();
            } catch (Exception e) {
                Slog.e(TAG, "Could not load Axion device policy", e);
            }
            if (mKernel == null) return;
            ContentObserver observer = new ContentObserver(mWorker) {
                @Override
                public void onChange(boolean selfChange) {
                    updateSettings();
                }
            };
            context.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(ENABLED), false, observer);
            context.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(AFFINITY), false, observer);
            context.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(REALTIME), false, observer);
            updateSettings();
        });
    }

    private void updateSettings() {
        mEnabled = Settings.Global.getInt(mContext.getContentResolver(), ENABLED, 1) != 0;
        mAffinity = Settings.Global.getInt(mContext.getContentResolver(), AFFINITY, 1) != 0;
        mRealtime = Settings.Global.getInt(mContext.getContentResolver(), REALTIME, 0) != 0;
        if (!mEnabled) {
            for (int i = mLeases.size() - 1; i >= 0; i--) removeLeaseAt(i);
        }
        reconcile();
    }

    public static void request(Object owner, int scene, int pid, boolean active) {
        AxDragoniteService service = sService;
        if (service != null) service.post(owner, scene, pid, active);
    }

    public static void requestClient(IBinder owner, int scene, int pid, boolean active) {
        if (owner != null && pid > 0) request(owner, scene, pid, active);
    }

    private void post(Object owner, int id, int pid, boolean active) {
        final long requestTime = SystemClock.uptimeMillis();
        mWorker.post(() -> {
            if (!mEnabled) return;
            final AxKernelManager.Scene scene = mKernel.scenes.get(id);
            if (scene == null) return;
            Key key = new Key(Objects.requireNonNull(owner), id);
            int index = mLeases.indexOfKey(key);
            Lease previous = index >= 0 ? mLeases.valueAt(index) : null;
            if (previous != null && previous.pid != pid) return;
            if (!active) {
                if (previous == null) return;
                removeLeaseAt(index);
            } else {
                long expires = requestTime + scene.durationMs();
                if (expires <= SystemClock.uptimeMillis()) return;
                if (previous != null) {
                    if (expires <= previous.expires) return;
                    previous.expires = expires;
                } else {
                    if (mLeases.size() >= 128) return;
                    Lease lease = new Lease(key, pid, scene, expires);
                    if (owner instanceof IBinder binder) {
                        try {
                            binder.linkToDeath(lease, 0);
                        } catch (RemoteException e) {
                            return;
                        }
                    }
                    mLeases.put(key, lease);
                }
            }
            reconcile();
        });
    }

    private void removeLeaseAt(int index) {
        Lease lease = mLeases.removeAt(index);
        if (lease.key.owner() instanceof IBinder binder) binder.unlinkToDeath(lease, 0);
    }

    private void reconcile() {
        long now = SystemClock.uptimeMillis();
        long nextExpiry = Long.MAX_VALUE;
        long until = 0;
        long gpuUntil = 0;
        int level = AxKernelManager.NONE;
        mUiPids.clear();
        for (int i = mLeases.size() - 1; i >= 0; i--) {
            Lease lease = mLeases.valueAt(i);
            Object owner = mLeases.keyAt(i).owner();
            if (lease.expires <= now || (owner instanceof IBinder binder && !binder.isBinderAlive())) {
                removeLeaseAt(i);
                continue;
            }
            final int leaseLevel = lease.policy.level();
            nextExpiry = Math.min(nextExpiry, lease.expires);
            if (leaseLevel > level) {
                level = leaseLevel;
                until = lease.expires;
            } else if (leaseLevel == level) {
                until = Math.max(until, lease.expires);
            }
            if (lease.policy.boostRendering()) {
                gpuUntil = Math.max(gpuUntil, lease.expires);
            }
            if (mAffinity && mKernel.performanceMask != 0 && lease.policy.placeUi()
                    && lease.pid > 0 && lease.pid != Process.myPid()) {
                mUiPids.put(lease.pid, Math.max(mUiPids.get(lease.pid), leaseLevel));
            }
        }
        for (int i = mPlacements.size() - 1; i >= 0; i--) {
            if (mUiPids.indexOfKey(mPlacements.keyAt(i)) < 0) {
                mPlacements.valueAt(i).restore();
                mPlacements.removeAt(i);
            }
        }
        for (int i = 0; i < mUiPids.size(); i++) {
            int pid = mUiPids.keyAt(i);
            AxUiPlacement placement = mPlacements.get(pid);
            if (placement == null) {
                placement = new AxUiPlacement(pid, mKernel.performanceMask);
                mPlacements.put(pid, placement);
            }
            placement.refresh();
            if (mRealtime && mUiPids.valueAt(i) == AxKernelManager.HEAVY) {
                nextExpiry = Math.min(nextExpiry, placement.reconcileRealtime(now));
            } else {
                placement.stopRealtime();
            }
        }
        mKernel.apply(level, until, gpuUntil);
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "AxDragonite level", level);
        Trace.traceCounter(Trace.TRACE_TAG_POWER, "AxDragonite leases", mLeases.size());
        mWorker.removeCallbacks(mExpire);
        if (nextExpiry != Long.MAX_VALUE) mWorker.postAtTime(mExpire, nextExpiry);
    }

    public static void dumpState(PrintWriter pw) {
        AxDragoniteService service = sService;
        if (service == null) return;
        StringWriter state = new StringWriter();
        if (service.mWorker.runWithScissors(() -> service.dumpOnWorker(new PrintWriter(state)), 500)) {
            pw.print(state);
        } else {
            pw.println("AxDragonite worker busy");
        }
    }

    private void dumpOnWorker(PrintWriter pw) {
        pw.println("AxDragonite enabled=" + mEnabled + " affinity=" + mAffinity
                + " leases=" + mLeases.size() + " placedProcesses=" + mPlacements.size());
        if (mKernel == null) return;
        mKernel.dump(pw);
        for (int i = 0; i < mLeases.size(); i++) {
            Lease lease = mLeases.valueAt(i);
            pw.println("  scene=" + lease.key.scene() + " pid=" + lease.pid
                    + " level=" + lease.policy.level() + " rendering=" + lease.policy.boostRendering()
                    + " expiresUptime=" + lease.expires);
        }
    }
}
