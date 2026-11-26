/*
 * Copyright 2025-2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.dragonite.AxDragonite;
import com.android.server.LocalServices;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** SystemServer scene coordinator, adapted from Axion's Dragonite session records. */
public final class AxDragoniteService {
    private static final String TAG = "AxDragoniteService";
    private static final String ENABLED = "ax_dragonite_enabled";
    private static final String AFFINITY = "ax_dragonite_affinity";
    private static final String REALTIME = "ax_dragonite_realtime";
    private static final String THREAD_POLICY = "ax_dragonite_thread_policy";
    private static final String MEMORY_PROFILE = "ax_dragonite_memory_profile";
    private static final String[] SETTINGS = {ENABLED, AFFINITY, REALTIME, THREAD_POLICY,
            AxKernelManager.GOVERNOR, AxKernelManager.CPU_PROFILE, AxKernelManager.GPU_PROFILE,
            MEMORY_PROFILE};
    private static final long HAL_RECONNECT_MS = 5_000;
    private static volatile AxDragoniteService sService;
    private static volatile GamePolicy sGamePolicy;

    public interface GamePolicy {
        boolean isPerformanceGame(String packageName, int uid);
    }

    private record TopGame(String packageName, int uid, int pid, IBinder process) {}

    public static void registerGamePolicy(GamePolicy policy) {
        sGamePolicy = policy;
        gamePolicyChanged();
    }

    public static void gamePolicyChanged() {
        AxDragoniteService service = sService;
        if (service != null) service.mWorker.post(service.mChanged);
    }

    public static void gameUidChanged(int uid) {
        AxDragoniteService service = sService;
        TopGame top = service == null ? null : service.mTopGame;
        if (top != null && top.uid() == uid) service.mWorker.post(service.mChanged);
    }

    public static void gameUserSwitching(int userId) {
        AxDragoniteService service = sService;
        if (service == null) return;
        service.mForegroundUser = userId;
        service.mWorker.post(service.mChanged);
    }

    /** Called under the WM lock for the focused, top-resumed activity only. */
    public static void setTopGame(String name, int uid, int pid, IBinder process) {
        AxDragoniteService service = sService;
        if (service == null) return;
        TopGame old = service.mTopGame;
        if (name == null || pid <= 0 || pid == Process.myPid() || process == null) {
            if (old == null) return;
            service.mTopGame = null;
        } else {
            if (old != null && old.process() == process && old.uid() == uid && old.pid() == pid
                    && old.packageName().equals(name)) return;
            service.mTopGame = new TopGame(name, uid, pid, process);
        }
        service.mWorker.post(service.mChanged);
    }

    /** Binder caller identity sampled before GameManagerService hands the request to its handler. */
    public record GameCaller(int pid, int uid, long startTime, long requestedAt) {}

    public static GameCaller captureGameCaller() {
        if (sService == null) return null;
        long requestedAt = SystemClock.uptimeMillis();
        int pid = Binder.getCallingPid();
        if (pid <= 0 || pid == Process.myPid()) return null;
        long startTime = AxUiPlacement.startTime(pid, pid);
        return startTime < 0 ? null : new GameCaller(pid, Binder.getCallingUid(), startTime,
                requestedAt);
    }

    public static void requestGameLoading(String packageName, int packageUid, GameCaller caller,
            boolean active) {
        AxDragoniteService service = sService;
        if (service == null || caller == null || caller.uid() != packageUid) return;
        service.mWorker.post(() -> {
            if (!service.mEnabled) return;
            AxKernelManager.Scene scene = service.mKernel.scenes.get(AxDragonite.GAME_LOADING);
            if (scene == null) return;
            long until = caller.requestedAt() + scene.durationMs();
            if (active && until <= SystemClock.uptimeMillis()) return;
            service.updateGameSession();
            service.mGame.loading(packageName, caller.uid(), caller.pid(), caller.startTime(),
                    active, until);
            service.reconcile();
        });
    }

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
    private final AxGameSession mGame = new AxGameSession();
    private volatile TopGame mTopGame;
    private volatile int mForegroundUser = UserHandle.USER_NULL;
    private TopGame mObservedTop;
    private long mObservedStartTime;
    private AxUiPlacement mGamePlacement;
    private final SparseArray<AxUiPlacement> mPlacements = new SparseArray<>();
    private final Runnable mExpire = this::reconcile;
    // External invalidations must not be removed when rescheduling the expiry timer.
    private final Runnable mChanged = this::reconcile;
    private final Consumer<IBinder> mOnHalDied = this::onHalDied;
    private AxKernelManager mKernel;
    private boolean mEnabled;
    private boolean mAffinity;
    private boolean mRealtime;
    private boolean mFairBoost;
    private volatile boolean mRetainCachedActivities;

    public static boolean isConfigured() {
        return AxKernelManager.isConfigured();
    }

    /** Retain warm activities by restricting freezer-triggered compaction to empty processes. */
    public static boolean retainCachedActivities() {
        AxDragoniteService service = sService;
        return service != null && service.mRetainCachedActivities;
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
            ContentResolver resolver = context.getContentResolver();
            for (String setting : SETTINGS) {
                resolver.registerContentObserver(Settings.Global.getUriFor(setting), false, observer);
            }
            updateSettings();
        });
    }

    private void onHalDied(IBinder binder) {
        mWorker.post(() -> {
            mKernel.onHalDied(binder);
            reconcile();
        });
    }

    private void updateSettings() {
        ContentResolver resolver = mContext.getContentResolver();
        mEnabled = Settings.Global.getInt(resolver, ENABLED, 1) != 0;
        mAffinity = Settings.Global.getInt(resolver, AFFINITY, 1) != 0;
        mRealtime = Settings.Global.getInt(resolver, REALTIME, 0) != 0;
        mFairBoost = "fair".equals(Settings.Global.getString(resolver, THREAD_POLICY));
        mRetainCachedActivities = mEnabled
                && "retain-activities".equals(Settings.Global.getString(resolver, MEMORY_PROFILE));
        mKernel.setProfiles(Settings.Global.getString(resolver, AxKernelManager.GOVERNOR),
                Settings.Global.getString(resolver, AxKernelManager.CPU_PROFILE),
                Settings.Global.getString(resolver, AxKernelManager.GPU_PROFILE), mEnabled);
        if (!mEnabled) {
            for (int i = mLeases.size() - 1; i >= 0; i--) removeLeaseAt(i);
        }
        reconcile();
    }

    public static void request(Object owner, int scene, int pid, boolean active) {
        AxDragoniteService service = sService;
        if (service != null && scene != AxDragonite.GAME_LOADING) {
            service.post(owner, scene, pid, active);
        }
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

    private void updateGameSession() {
        TopGame top = mTopGame;
        GamePolicy policy = sGamePolicy;
        ActivityManagerInternal am = LocalServices.getService(ActivityManagerInternal.class);
        if (!mEnabled || !mKernel.foregroundGame || top == null || am == null || policy == null) {
            mObservedTop = null;
            mGame.clear();
            return;
        }
        long start = AxUiPlacement.startTime(top.pid(), top.pid());
        if (top != mObservedTop) {
            mObservedTop = top;
            mObservedStartTime = start;
            mGame.clear();
        } else if (mObservedStartTime < 0) {
            // A failed proc read on the first notification is not a process generation.
            mObservedStartTime = start;
        }
        if (start < 0 || start != mObservedStartTime || !top.process().isBinderAlive()
                || Process.getUidForPid(top.pid()) != top.uid()) {
            mGame.clear();
            return;
        }
        int user = UserHandle.getUserId(top.uid());
        int foregroundUser = mForegroundUser;
        boolean currentUser = user == am.getCurrentUserId()
                && (foregroundUser == UserHandle.USER_NULL || foregroundUser == user);
        boolean topUid = currentUser
                && am.getUidProcessState(top.uid()) == ActivityManager.PROCESS_STATE_TOP;
        mGame.update(top.packageName(), top.uid(), top.pid(), start, currentUser, topUid,
                topUid && policy.isPerformanceGame(top.packageName(), top.uid()));
    }

    private static boolean mayUseTopAppCpuset(int pid) {
        // SystemUI normally lives in another group. Unknown membership must yield to UI.
        try {
            String group = Files.readString(Path.of("/proc/" + pid + "/cpuset")).trim();
            return group.isEmpty() || group.equals("/top-app") || group.startsWith("/top-app/");
        } catch (IOException e) {
            return true;
        }
    }

    private void reconcile() {
        if (mKernel == null) return;
        updateGameSession();
        long now = SystemClock.uptimeMillis();
        long nextExpiry = Long.MAX_VALUE;
        long until = 0;
        long gpuUntil = 0;
        long cameraUntil = 0;
        long topAppUntil = 0;
        long backgroundUntil = 0;
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
            if (lease.policy.limitCameraCpuset()) {
                cameraUntil = Math.max(cameraUntil, lease.expires);
            }
            if (lease.policy.limitTopAppCpuset()) {
                topAppUntil = Math.max(topAppUntil, lease.expires);
            }
            if (lease.policy.limitBackgroundCpuset()) {
                backgroundUntil = Math.max(backgroundUntil, lease.expires);
            }
            if (mAffinity && mKernel.performanceMask != 0 && lease.policy.placeUi()
                    && lease.pid > 0 && lease.pid != Process.myPid()) {
                mUiPids.put(lease.pid, Math.max(mUiPids.get(lease.pid), leaseLevel));
            }
        }
        long loadingUntil = mGame.loadingUntil(now);
        if (loadingUntil != 0) {
            AxKernelManager.Scene loading = mKernel.scenes.get(AxDragonite.GAME_LOADING);
            nextExpiry = Math.min(nextExpiry, loadingUntil);
            if (loading.level() > level) {
                level = loading.level();
                until = loadingUntil;
            } else if (loading.level() == level) {
                until = Math.max(until, loadingUntil);
            }
            if (loading.boostRendering()) gpuUntil = Math.max(gpuUntil, loadingUntil);
        }
        AxGameSession.Game game = mGame.game();
        boolean placeGame = game != null && mAffinity && mKernel.performanceMask != 0
                && mKernel.gameWorkerMask != 0;
        boolean topAppConflict = false;
        if (topAppUntil != 0 && !placeGame) {
            for (int i = 0; i < mUiPids.size() && !topAppConflict; i++) {
                topAppConflict = mayUseTopAppCpuset(mUiPids.keyAt(i));
            }
        }
        topAppUntil = AxGameSession.topAppIsolationUntil(topAppUntil, placeGame, topAppConflict);

        // UI interaction wins even when its big-core request comes from a different process.
        boolean gamePlacementWanted = placeGame && mUiPids.size() == 0;
        if (mGamePlacement != null && (!gamePlacementWanted
                || !mGamePlacement.isGameProcess(game.pid(), game.startTime()))) {
            mGamePlacement.restore();
            mGamePlacement = null;
        }
        for (int i = mPlacements.size() - 1; i >= 0; i--) {
            int pid = mPlacements.keyAt(i);
            if (mUiPids.indexOfKey(pid) < 0) {
                mPlacements.valueAt(i).restore();
                mPlacements.removeAt(i);
            }
        }
        if (!mKernel.apply(level, until, gpuUntil, cameraUntil, topAppUntil, backgroundUntil,
                mOnHalDied)) {
            nextExpiry = Math.min(nextExpiry, now + HAL_RECONNECT_MS);
        }
        for (int i = 0; i < mUiPids.size(); i++) {
            int pid = mUiPids.keyAt(i);
            AxUiPlacement placement = mPlacements.get(pid);
            if (placement == null) {
                placement = new AxUiPlacement(pid, mKernel.performanceMask);
                mPlacements.put(pid, placement);
            }
            nextExpiry = Math.min(nextExpiry, placement.refresh(now));
            boolean heavy = mUiPids.valueAt(i) == AxKernelManager.HEAVY;
            if (heavy && mRealtime && !mFairBoost) {
                placement.stopFairBoost();
                nextExpiry = Math.min(nextExpiry, placement.reconcileRealtime(now));
            } else {
                placement.stopRealtime();
                if (heavy && mFairBoost) {
                    placement.boostFairPriority();
                } else {
                    placement.stopFairBoost();
                }
            }
        }
        if (gamePlacementWanted) {
            if (mGamePlacement == null) {
                mGamePlacement = new AxUiPlacement(game.pid(), mKernel.performanceMask,
                        mKernel.gameWorkerMask, game.startTime());
            }
            nextExpiry = Math.min(nextExpiry, mGamePlacement.refreshGame(now));
        }
        if (game != null) {
            nextExpiry = Math.min(nextExpiry, now + AxUiPlacement.GAME_DISCOVERY_INTERVAL_MS);
        }
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
            // Read kernel telemetry on the dump thread, outside the scene worker.
            AxKernelMetrics.dump(pw);
        } else {
            pw.println("AxDragonite worker busy");
        }
    }

    private void dumpOnWorker(PrintWriter pw) {
        pw.println("AxDragonite enabled=" + mEnabled + " affinity=" + mAffinity
                + " leases=" + mLeases.size() + " placedProcesses=" + mPlacements.size());
        if (mKernel == null) return;
        mKernel.dump(pw);
        pw.println("  foregroundGame=" + mGame.game() + " placed=" + (mGamePlacement != null)
                + " loadingUntil=" + mGame.loadingUntil(SystemClock.uptimeMillis()));
        for (int i = 0; i < mLeases.size(); i++) {
            Lease lease = mLeases.valueAt(i);
            pw.println("  scene=" + lease.key.scene() + " pid=" + lease.pid
                    + " level=" + lease.policy.level() + " rendering=" + lease.policy.boostRendering()
                    + " cameraCpuset=" + lease.policy.limitCameraCpuset()
                    + " topAppCpuset=" + lease.policy.limitTopAppCpuset()
                    + " backgroundCpuset=" + lease.policy.limitBackgroundCpuset()
                    + " expiresUptime=" + lease.expires);
        }
    }
}
