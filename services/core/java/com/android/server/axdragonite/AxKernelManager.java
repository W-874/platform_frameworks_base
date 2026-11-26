/*
 * Copyright 2025-2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import google.hardware.power.extension.pixel.IPowerExt;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/** Loads the device's Axion policy and submits all scene requests to its Power HAL. */
final class AxKernelManager {
    private static final File POLICY_FILE = new File(Environment.getProductDirectory(),
            "etc/ax_dragonite.xml");
    static final int NONE = 0;
    static final int LIGHT = 1;
    static final int HEAVY = 2;
    private static final String TAG = "AxKernelManager";
    private static final String HAL_SERVICE = "android.hardware.power.IPower/default";
    private static final String[] HINTS = {null, "AX_DRAGONITE_LIGHT", "AX_DRAGONITE_HEAVY"};

    record Scene(int level, int durationMs, boolean placeUi) {}

    final SparseArray<Scene> scenes = new SparseArray<>();
    private final int[] mPolicyIds;
    final long performanceMask;
    private IPowerExt mHal;
    private int mLevel;
    private long mUntil;
    static boolean isConfigured() {
        return POLICY_FILE.isFile();
    }

    private AxKernelManager(int[] policyIds) {
        mPolicyIds = policyIds;
        performanceMask = detectPerformanceMask(policyIds);
    }

    static AxKernelManager load() throws IOException, XmlPullParserException {
        if (!isConfigured()) return null;
        try (FileInputStream input = new FileInputStream(POLICY_FILE)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, null);
            XmlUtils.beginDocument(parser, "dragonite");
            int[] policyIds = Arrays.stream(parser.getAttributeValue(null, "cpuPolicies")
                    .split(",")).mapToInt(Integer::parseInt).toArray();
            AxKernelManager manager = new AxKernelManager(policyIds);
            final int depth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, depth)) {
                if (!"scene".equals(parser.getName())) {
                    throw new XmlPullParserException("Expected scene rule");
                }
                int id = Integer.parseInt(parser.getAttributeValue(null, "id"));
                int duration = Integer.parseInt(parser.getAttributeValue(null, "durationMs"));
                int level = switch (parser.getAttributeValue(null, "level")) {
                    case "light" -> LIGHT;
                    case "heavy" -> HEAVY;
                    default -> throw new XmlPullParserException("Invalid boost level");
                };
                if (id <= 0 || duration < 100 || duration > 1500
                        || manager.scenes.get(id) != null) {
                    throw new XmlPullParserException("Invalid or duplicate scene " + id);
                }
                manager.scenes.put(id, new Scene(level, duration,
                        Boolean.parseBoolean(parser.getAttributeValue(null, "placeUi"))));
            }
            return manager;
        }
    }

    private static long detectPerformanceMask(int[] policyIds) {
        int bestCapacity = 0;
        long mask = 0;
        try {
            for (int policyId : policyIds) {
                String cpus = readLine("/sys/devices/system/cpu/cpufreq/policy" + policyId
                        + "/related_cpus");
                long policyMask = 0;
                for (String id : cpus.split("\\s+")) {
                    int cpu = Integer.parseInt(id);
                    if (cpu < 0 || cpu >= Long.SIZE) return 0;
                    policyMask |= 1L << cpu;
                }
                int capacity = Integer.parseInt(readLine("/sys/devices/system/cpu/cpu" + policyId
                        + "/cpu_capacity"));
                if (capacity > bestCapacity) {
                    bestCapacity = capacity;
                    mask = policyMask;
                } else if (capacity == bestCapacity) {
                    mask |= policyMask;
                }
            }
        } catch (IOException | NumberFormatException e) {
            Slog.w(TAG, "CPU topology unavailable; leaving thread placement to Android", e);
            return 0;
        }
        return mask;
    }

    private static String readLine(String path) throws IOException {
        try (FileInputStream input = new FileInputStream(path)) {
            return new String(input.readNBytes(256), java.nio.charset.StandardCharsets.US_ASCII)
                    .trim();
        }
    }

    // Called on the Dragonite worker only. libperfmgr remains the sole shared node writer.
    void apply(int level, long until, long now) {
        if (level == NONE && mLevel == NONE) return;
        if (mHal != null && level == mLevel && mUntil == until) return;
        try {
            if (mHal == null) {
                IBinder binder = ServiceManager.checkService(HAL_SERVICE);
                if (binder == null) return;
                mHal = IPowerExt.Stub.asInterface(binder.getExtension());
                if (mHal == null) return;
            }
            if (level != NONE) {
                mHal.setBoost(HINTS[level], (int) Math.max(1, until - now));
            }
            if (level != mLevel && mLevel != NONE) mHal.setBoost(HINTS[mLevel], -1);
            mLevel = level;
            mUntil = until;
        } catch (RemoteException e) {
            mHal = null;
            Slog.w(TAG, "Power HAL extension unavailable", e);
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  policies=" + Arrays.toString(mPolicyIds)
                + " performanceMask=0x" + Long.toHexString(performanceMask));
        pw.println("  hint=" + HINTS[mLevel] + " expiresUptime=" + mUntil
                + " halConnected=" + (mHal != null));
    }
}
