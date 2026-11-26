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

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LruCache;

import com.android.internal.os.BackgroundThread;

import dalvik.system.DexFile;
import dalvik.system.VMRuntime;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Axion memory policy adapted to the Android process and OOM lifecycle. */
final class AxMemoryManager {
    static final String ENABLED_SETTING = "ax_memory_policy_enabled";
    private static final int NO_USER = UserHandle.USER_NULL;
    private static final long HISTORY_MS = TimeUnit.DAYS.toMillis(7);
    private static final long CAMERA_THROTTLE_MS = 5_000;
    private static final long FACE_THROTTLE_MS = 5_000;
    private static final long SCREEN_THROTTLE_MS = TimeUnit.HOURS.toMillis(1);
    private static final long PREFORK_STAGGER_MS = 3_000;
    private static final int PRELOAD_BYTES = 4 * 1024 * 1024;
    private static final Set<String> CAMERA_PACKAGES = Set.of("org.lineageos.aperture",
            "com.google.android.GoogleCamera", "com.google.android.apps.googlecamera.fishfood",
            "com.android.camera", "com.android.camera2");
    private static final Set<String> PROTECTED_PACKAGES = Set.of("com.android.systemui",
            "com.android.launcher3", "com.google.android.apps.nexuslauncher", "com.android.launcher",
            "com.android.axion.gamespace", "com.android.settings", "com.android.phone",
            "com.google.android.gms", "com.android.chrome", "com.xiaomi.xmsf");

    private final ActivityManagerService mAm;
    final long mRamBytes = Process.getTotalMemory();
    private final ArraySet<ProcessRecord> mPreforked = new ArraySet<>(); // AMS locks
    private final LruCache<App, Long> mCachedPss = new LruCache<>(64);
    private final LruCache<App, Long> mRemoved = new LruCache<>(64);
    private final Runnable mRefresh = this::refresh;
    private volatile boolean mEnabled;
    private volatile Ranking mRanking = new Ranking(NO_USER, Map.of());
    private volatile App mTopCamera;
    private volatile CameraStart mCameraStart;
    private long mLastScreen, mLastFace, mLastCamera; // AMS handler; stored as deadlines
    private int mReleaseUser = NO_USER; // AMS handler only
    private int mPreforkUser = NO_USER; // background handler only
    private long mLastPreforkUptime; // background handler only
    private volatile int mHomeFocusedUser = NO_USER;

    private record App(int uid, String pkg) {}
    private record Rank(int adj, long pss) {}
    private record CameraStart(int userId, long until) {}
    private record Ranking(int userId, Map<App, Rank> ranks) {}
    private record Candidate(ProcessRecord app, int adj, long rss) {}
    private enum Release { CAMERA, FACE, SCREEN, EXPLICIT }

    AxMemoryManager(ActivityManagerService am) {
        mAm = am;
    }

    void systemReady() {
        final var resolver = mAm.mContext.getContentResolver();
        resolver.registerContentObserver(Settings.Global.getUriFor(ENABLED_SETTING), false,
                new ContentObserver(BackgroundThread.getHandler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        updateEnabled();
                    }
                });
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        mAm.mContext.registerReceiverForAllUsers(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    postRelease(Release.SCREEN, false, mAm.mUserController.getCurrentUserId());
                } else if (mEnabled) {
                    if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                        mAm.mHandler.post(mAm::updateAxMemoryOomAdj);
                    }
                    scheduleRefresh(30_000);
                }
            }
        }, filter, null, BackgroundThread.getHandler(), Context.RECEIVER_NOT_EXPORTED);
        BackgroundThread.getHandler().post(this::updateEnabled);
    }

    private void updateEnabled() {
        final boolean enabledByDefault = mAm.mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_axMemoryPolicyEnabledByDefault);
        mEnabled = Settings.Global.getInt(mAm.mContext.getContentResolver(), ENABLED_SETTING,
                enabledByDefault ? 1 : 0) != 0;
        if (mEnabled) {
            scheduleRefresh(30_000);
        } else {
            BackgroundThread.getHandler().removeCallbacks(mRefresh);
            mRanking = new Ranking(NO_USER, Map.of());
            mTopCamera = null;
            mCameraStart = null;
            mPreforkUser = NO_USER;
            mAm.mHandler.post(mAm::updateAxMemoryOomAdj);
        }
    }

    private void scheduleRefresh(long delay) {
        final var handler = BackgroundThread.getHandler();
        handler.removeCallbacks(mRefresh);
        handler.postDelayed(mRefresh, delay);
    }

    private void refresh() {
        if (!mEnabled) return;
        final int user = mAm.mUserController.getCurrentUserId();
        final Context context = mAm.mContext.createContextAsUser(UserHandle.of(user), 0);
        if (!context.getSystemService(UserManager.class).isUserUnlocked()) {
            scheduleRefresh(AxMemoryPolicy.LMK_WINDOW_MS);
            return;
        }
        final UsageStatsManager usage = context.getSystemService(UsageStatsManager.class);
        final long now = System.currentTimeMillis();
        final List<UsageStats> stats = new ArrayList<>(
                usage.queryAndAggregateUsageStats(now - HISTORY_MS, now).values());
        stats.sort(Comparator.comparingLong(UsageStats::getTotalTimeInForeground).reversed()
                .thenComparing(Comparator.comparingLong(UsageStats::getLastTimeUsed).reversed())
                .thenComparing(UsageStats::getPackageName));
        final PackageManager pm = context.getPackageManager();
        final ArrayMap<App, Rank> ranks = new ArrayMap<>();
        final ArrayList<ApplicationInfo> preforks = new ArrayList<>();
        final ArrayList<ApplicationExitInfo> exits = new ArrayList<>();
        long pssBudget = AxMemoryPolicy.preforkBudgetKb(mRamBytes);
        for (UsageStats stat : stats) {
            if (stat.getTotalTimeInForeground() <= 0 || ranks.size() == AxMemoryPolicy.MAX_PREFORKS) {
                break;
            }
            final String pkg = stat.getPackageName();
            if (PROTECTED_PACKAGES.contains(pkg)) continue;
            final Intent launch = pm.getLaunchIntentForPackage(pkg);
            final ResolveInfo ri = launch == null ? null : pm.resolveActivity(launch, 0);
            if (ri == null || ri.activityInfo == null) continue;
            final ActivityInfo activity = ri.activityInfo;
            final ApplicationInfo info = activity.applicationInfo;
            final App app = new App(info.uid, pkg);
            if (!Process.isApplicationUid(info.uid) || !info.enabled || info.isInstantApp()
                    || (info.flags & (ApplicationInfo.FLAG_STOPPED
                            | ApplicationInfo.FLAG_PERSISTENT)) != 0
                    || !pkg.equals(info.packageName)
                    || !info.processName.equals(activity.processName) || recentlyRemoved(app)) {
                continue;
            }
            exits.clear();
            mAm.mProcessList.mAppExitInfoTracker.getExitInfo(pkg, info.uid, 0, 0, exits);
            int recentLmkCount = 0;
            final Long sampledPss = mCachedPss.get(app);
            long pss = sampledPss == null ? 0 : sampledPss;
            for (ApplicationExitInfo exit : exits) {
                if (!info.processName.equals(exit.getProcessName())) continue;
                if (exit.getSubReason() == ApplicationExitInfo.SUBREASON_REMOVE_TASK
                        && exit.getTimestamp() > now - AxMemoryPolicy.REMOVE_COOLDOWN_MS) {
                    recentlyRemoved(app, SystemClock.elapsedRealtime()
                            - Math.max(0, now - exit.getTimestamp()));
                }
                if (exit.getReason() == ApplicationExitInfo.REASON_LOW_MEMORY
                        && exit.getTimestamp() > now - AxMemoryPolicy.LMK_WINDOW_MS) {
                    if (pss == 0) pss = exit.getPss();
                    recentLmkCount++;
                }
            }
            if (recentlyRemoved(app)) continue;
            ranks.put(app, new Rank(AxMemoryPolicy.usageAdj(recentLmkCount, pss), pss));
            final long estimate = pss > 0 ? pss : AxMemoryPolicy.UNKNOWN_PSS_KB;
            if (estimate <= pssBudget) {
                pssBudget -= estimate;
                preforks.add(info);
            }
        }
        if (mAm.mUserController.getCurrentUserId() == user) {
            final Ranking ranking = new Ranking(user, Map.copyOf(ranks));
            mRanking = ranking;
            mAm.mHandler.post(mAm::updateAxMemoryOomAdj);
            final long uptime = SystemClock.uptimeMillis();
            if (mPreforkUser != user || (mHomeFocusedUser == user
                    && uptime - mLastPreforkUptime >= AxMemoryPolicy.LMK_WINDOW_MS)) {
                mLastPreforkUptime = uptime;
                mPreforkUser = user;
                for (int i = 0; i < preforks.size(); i++) {
                    final ApplicationInfo info = preforks.get(i);
                    mAm.mHandler.postDelayed(() -> {
                        if (mRanking == ranking) mAm.startAxPrefork(info);
                    }, (i + 1) * PREFORK_STAGGER_MS);
                }
            }
        }
        scheduleRefresh(AxMemoryPolicy.LMK_WINDOW_MS);
    }

    boolean canPrefork(ApplicationInfo info) {
        return mEnabled && mAm.mBooted && mRanking.userId == UserHandle.getUserId(info.uid)
                && mRanking.ranks.containsKey(new App(info.uid, info.packageName))
                && !recentlyRemoved(new App(info.uid, info.packageName))
                && !isCameraScene() && mPreforked.size() < AxMemoryPolicy.MAX_PREFORKS;
    }

    void onPrefork(ProcessRecord app) {
        mPreforked.add(app);
        app.mAxPrefork = true;
        final long seq = app.getStartSeq();
        mAm.mHandler.postDelayed(() -> mAm.expireAxPrefork(app, seq),
                AxMemoryPolicy.PREFORK_LIFETIME_MS);
    }

    boolean isEnabledForUser(int userId) {
        return mEnabled && userId == mAm.mUserController.getCurrentUserId();
    }

    void clearInactivePreforks() {
        for (int i = mPreforked.size() - 1; i >= 0; i--) {
            final ProcessRecord app = mPreforked.valueAt(i);
            if (!isEnabledForUser(app.userId)) clearPrefork(app);
        }
    }

    void clearPrefork(ProcessRecord app) {
        if (app.mAxPrefork) {
            app.mAxPrefork = false;
            mPreforked.remove(app);
        }
    }

    void onDeath(ProcessRecord app) {
        clearPrefork(app);
        final long pss = app.mProfile.getLastCachedPss();
        if (mEnabled && pss > 0 && app.processName.equals(app.info.processName)) {
            mCachedPss.put(new App(app.uid, app.info.packageName), pss);
        }
    }

    void onTaskRemoved(int uid, String pkg) {
        if (mEnabled) recentlyRemoved(new App(uid, pkg), SystemClock.elapsedRealtime());
    }

    private void recentlyRemoved(App app, long time) {
        final Long last = mRemoved.get(app);
        if (last == null || time > last) mRemoved.put(app, time);
    }

    private boolean recentlyRemoved(App app) {
        final Long last = mRemoved.get(app);
        return last != null && SystemClock.elapsedRealtime() - last < AxMemoryPolicy.REMOVE_COOLDOWN_MS;
    }

    int adjustOom(ProcessRecord app, int adj) {
        if (!isEnabledForUser(app.userId) || app.isPersistent()) return adj;
        if (app.mAxPrefork && (app.getHasShownUi() || app.getHasStartedServices())) {
            clearPrefork(app);
        }
        if ("fg-service".equals(app.getAdjType())) {
            return AxMemoryPolicy.foregroundServiceAdj(app.info.packageName, app.processName,
                    app.info.isSystemApp(), adj);
        }
        if (adj < AxMemoryPolicy.CACHED_MIN_ADJ) return adj;
        if (!app.getHasShownUi() && !app.mAxPrefork) return adj;
        final String pkg = app.info.packageName;
        if ("com.android.settings".equals(pkg)) return adj;
        final App key = new App(app.uid, pkg);
        final Rank rank = mRanking.ranks.get(key);
        return AxMemoryPolicy.cachedAdj(adj, app.mAxPrefork, app.getHasShownUi(),
                recentlyRemoved(key), isGame(app.info), rank == null ? -1 : rank.adj);
    }

    static boolean isGame(ApplicationInfo info) {
        return info.category == ApplicationInfo.CATEGORY_GAME
                || (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
    }

    private static boolean isCamera(ApplicationInfo info) {
        // Never infer a privileged lifecycle request from a package-name substring.
        return info.isSystemApp() && CAMERA_PACKAGES.contains(info.packageName);
    }

    void onTopActivity(ApplicationInfo info, boolean home) {
        final int userId = UserHandle.getUserId(info.uid);
        mHomeFocusedUser = home ? userId : NO_USER;
        if (isEnabledForUser(userId) && home) {
            BackgroundThread.getHandler().post(() -> {
                if (SystemClock.uptimeMillis() - mLastPreforkUptime >= AxMemoryPolicy.LMK_WINDOW_MS) {
                    scheduleRefresh(PREFORK_STAGGER_MS);
                }
            });
        }
        if (!isEnabledForUser(userId)) return;
        if (isCamera(info)) {
            final App top = new App(info.uid, info.packageName);
            if (!top.equals(mTopCamera)) {
                mTopCamera = top;
                postRelease(Release.CAMERA, false, userId);
            }
        } else {
            mTopCamera = null;
        }
    }

    boolean isCameraScene() {
        final App camera = mTopCamera;
        final CameraStart start = mCameraStart;
        final int userId = mAm.mUserController.getCurrentUserId();
        return mEnabled && ((start != null && start.userId == userId
                && SystemClock.uptimeMillis() < start.until)
                || (camera != null && UserHandle.getUserId(camera.uid) == userId));
    }

    long cameraServiceRestartDelay() {
        return isCameraScene() ? PREFORK_STAGGER_MS : 0;
    }

    void onActivityProcessStart(ApplicationInfo info) {
        final int userId = UserHandle.getUserId(info.uid);
        if (!isEnabledForUser(userId)) return;
        if (isCamera(info)) {
            mCameraStart = new CameraStart(userId, SystemClock.uptimeMillis() + CAMERA_THROTTLE_MS);
            postRelease(Release.CAMERA, true, userId);
        }
        BackgroundThread.getHandler().post(() -> preloadCode(info));
    }

    private void preloadCode(ApplicationInfo info) {
        if (!isEnabledForUser(UserHandle.getUserId(info.uid)) || mAm.getMemoryTrimLevel() >= 2) {
            return;
        }
        // Bound speculative I/O and execute it off the AMS, WM and zygote threads.
        final byte[] buffer = new byte[64 * 1024];
        final int fileBudget = PRELOAD_BYTES / 2;
        final int remaining = readAhead(info.sourceDir, buffer, fileBudget);
        if (info.splitSourceDirs != null) {
            int budget = remaining;
            for (String path : info.splitSourceDirs) {
                if (budget == 0) break;
                budget = readAhead(path, buffer, budget);
            }
        }
        if (info.primaryCpuAbi == null) return;
        try {
            final String[] paths = DexFile.getDexFileOutputPaths(info.getBaseCodePath(),
                    VMRuntime.getInstructionSet(info.primaryCpuAbi));
            if (paths == null) return;
            int budget = fileBudget;
            for (String path : paths) {
                if (budget == 0) break;
                budget = readAhead(path, buffer, budget);
            }
        } catch (IOException ignored) {
            // An app need not have a dexopt artifact yet.
        }
    }

    private static int readAhead(String path, byte[] buffer, int remaining) {
        if (path == null) return remaining;
        try (FileInputStream file = new FileInputStream(path)) {
            while (remaining > 0) {
                final int read = file.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read <= 0) break;
                remaining -= read;
            }
        } catch (IOException ignored) {
            // The package can be replaced while a speculative read is queued.
        }
        return remaining;
    }

    void onFaceAuthentication() {
        postRelease(Release.FACE, false, mAm.mUserController.getCurrentUserId());
    }

    private void postRelease(Release release, boolean cold, int userId) {
        if (isEnabledForUser(userId)) {
            mAm.mHandler.post(() -> releaseForEvent(release, cold, userId));
        }
    }

    private void releaseForEvent(Release release, boolean cold, int userId) {
        if (!isEnabledForUser(userId) || !mAm.mBooted) return;
        if (mReleaseUser != userId) {
            mReleaseUser = userId;
            mLastCamera = mLastFace = mLastScreen = 0;
        }
        final long now = SystemClock.uptimeMillis();
        final int maxCount;
        switch (release) {
            case CAMERA -> {
                if (now < mLastCamera) return;
                mLastCamera = now + CAMERA_THROTTLE_MS;
                maxCount = AxMemoryPolicy.cameraCount(mRamBytes, cold);
            }
            case FACE -> {
                if (now < mLastFace) return;
                mLastFace = now + FACE_THROTTLE_MS;
                maxCount = 5;
            }
            case SCREEN -> {
                if (now < mLastScreen) return;
                mLastScreen = now + SCREEN_THROTTLE_MS;
                maxCount = AxMemoryPolicy.screenOnCount(mRamBytes);
            }
            default -> throw new IllegalArgumentException(release.name());
        }
        release(release, AxMemoryPolicy.CACHED_MIN_ADJ, maxCount, userId);
    }

    long releaseExplicit(int minAdj, int maxCount) {
        final int userId = mAm.mUserController.getCurrentUserId();
        if (!isEnabledForUser(userId)) return 0;
        long rssKb = release(Release.EXPLICIT, AxMemoryPolicy.releaseMinAdj(minAdj),
                AxMemoryPolicy.releaseCount(maxCount), userId);
        if (isEnabledForUser(userId)) mAm.getCachedAppOptimizer().compactAllSystem();
        return rssKb / 1024;
    }

    private long release(Release release, int minAdj, int maxCount, int userId) {
        return mAm.releaseAxMemory(userId, minAdj, maxCount,
                release == Release.CAMERA || release == Release.EXPLICIT,
                release == Release.EXPLICIT, release.name());
    }

    static boolean isReleaseCandidate(ProcessRecord app, ProcessRecord top, int userId,
            int minAdj, boolean allowCachedActivities) {
        final String pkg = app.info.packageName;
        return app.userId == userId && app != top && app.getPid() > 0 && !app.isPersistent()
                && !app.isKilledByAm()
                && app.isProcessRunning() && !app.getServices().hasForegroundServices()
                && !app.mReceivers.isReceivingBroadcast() && app.getSetAdj() >= minAdj
                && app.getCurAdj() >= minAdj && app.getLmkdAdj() >= minAdj
                && (allowCachedActivities || !app.hasActivities())
                && app.getSetProcState() > ActivityManager.PROCESS_STATE_RECEIVER
                && app.getProcState() > ActivityManager.PROCESS_STATE_RECEIVER
                && !PROTECTED_PACKAGES.contains(pkg)
                && !"com.google.android.googlequicksearchbox:search".equals(app.processName);
    }

    static List<ProcessRecord> rankVictims(List<ProcessRecord> lru, ProcessRecord top, int userId,
            int minAdj, boolean allowCachedActivities, boolean rankByRss) {
        final ArrayList<Candidate> candidates = new ArrayList<>();
        for (ProcessRecord app : lru) {
            if (isReleaseCandidate(app, top, userId, minAdj, allowCachedActivities)) {
                candidates.add(new Candidate(app, app.getLmkdAdj(), app.getLastRss()));
            }
        }
        candidates.sort(Comparator.comparingInt(Candidate::adj));
        if (rankByRss) {
            final ArrayMap<Candidate, Integer> adjRanks = ranks(candidates,
                    Comparator.comparingInt(Candidate::adj));
            final var rssOrder = Comparator.comparingLong(Candidate::rss);
            candidates.sort(rssOrder);
            final ArrayMap<Candidate, Integer> rssRanks = ranks(candidates, rssOrder);
            candidates.sort(Comparator.comparingInt(c ->
                    -AxMemoryPolicy.releaseScore(adjRanks.get(c), rssRanks.get(c))));
        } else {
            candidates.sort(Comparator.comparingInt(Candidate::adj).reversed());
        }
        final ArrayList<ProcessRecord> result = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) result.add(c.app);
        return result;
    }

    private static ArrayMap<Candidate, Integer> ranks(List<Candidate> list,
            Comparator<Candidate> order) {
        final ArrayMap<Candidate, Integer> ranks = new ArrayMap<>(list.size());
        int rank = 0;
        for (int i = 0; i < list.size(); i++) {
            if (i != 0 && order.compare(list.get(i - 1), list.get(i)) != 0) rank = i;
            ranks.put(list.get(i), rank);
        }
        return ranks;
    }
}
