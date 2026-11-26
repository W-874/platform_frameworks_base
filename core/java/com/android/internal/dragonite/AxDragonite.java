/*
 * Copyright 2025-2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.dragonite;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.SparseLongArray;

import com.android.internal.os.BackgroundThread;

/** Trusted in-process client for bounded scene leases. @hide */
public final class AxDragonite {
    // Scene IDs shared with the Axion SDK. Separate suffixed IDs allow independent
    // leases for scenes that were aliases upstream.
    public static final int APP_LAUNCH_COLD = 1;
    public static final int LAUNCHER_ANIMATION = 2;
    public static final int WAKE = 5;
    public static final int SHADE = 100;
    public static final int UNLOCK = 101;
    public static final int SYSTEMUI_ANIMATION = 102;
    public static final int APP_LAUNCH_WARM = 103;
    public static final int ROTATION = 104;
    public static final int CAMERA_LAUNCH = 201;
    public static final int GAME_LOADING = 300;
    public static final int FLING = 500;
    public static final int QUICK_SWITCH = 501;
    public static final int GESTURE = 502;
    public static final int QS = 503;
    public static final int NOTIFICATION_SCROLL = 504;
    public static final int BIOMETRIC = 505;
    public static final int DOZE = 506;
    public static final int VOLUME = 507;
    public static final int SHADE_FLING = 508;

    private static final Binder TOKEN = new Binder();
    private static final SparseLongArray LAST_PULSE = new SparseLongArray();
    private static final long MIN_REFRESH_MS = 96;

    private AxDragonite() {}

    /** Refresh a lease. The device policy, not the client, selects its lifetime. */
    public static void pulse(int scene) {
        long now = SystemClock.uptimeMillis();
        synchronized (LAST_PULSE) {
            if (now - LAST_PULSE.get(scene, -MIN_REFRESH_MS) < MIN_REFRESH_MS) return;
            LAST_PULSE.put(scene, now);
            enqueue(scene, true, now);
        }
    }

    /** End a previously requested scene without waiting for its timeout. */
    public static void end(int scene) {
        synchronized (LAST_PULSE) {
            int index = LAST_PULSE.indexOfKey(scene);
            if (index < 0) return;
            LAST_PULSE.removeAt(index);
            enqueue(scene, false, 0);
        }
    }

    private static void enqueue(int scene, boolean active, long eventUptime) {
        BackgroundThread.getHandler().post(() -> {
            if (!active || SystemClock.uptimeMillis() - eventUptime < MIN_REFRESH_MS) {
                request(scene, active);
            }
        });
    }

    private static void request(int scene, boolean active) {
        try {
            IActivityManager am = ActivityManager.getService();
            if (am != null) am.setAxDragoniteScene(TOKEN, scene, active);
        } catch (RemoteException ignored) {
            // A dying system_server cannot keep an active client lease.
        }
    }
}
