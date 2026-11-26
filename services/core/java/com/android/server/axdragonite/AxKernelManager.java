/*
 * Copyright 2025-2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import google.hardware.power.extension.pixel.IPowerExt;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;

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

    record Scene(int level, int durationMs, boolean placeUi) {}

    final SparseArray<Scene> scenes = new SparseArray<>();
    private final AxPowerVote mVote = new AxPowerVote(SystemClock::uptimeMillis);
    final long performanceMask;
    private AxPowerVote.Boost mBoost;

    static boolean isConfigured() {
        return POLICY_FILE.isFile();
    }

    private AxKernelManager(long performanceMask) {
        this.performanceMask = performanceMask;
    }

    static AxKernelManager load() throws IOException, XmlPullParserException {
        if (!isConfigured()) return null;
        try (FileInputStream input = new FileInputStream(POLICY_FILE)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, null);
            XmlUtils.beginDocument(parser, "dragonite");
            AxKernelManager manager = new AxKernelManager(AxKernelConfigLoader.loadPlacementMask());
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

    // Called on the Dragonite worker only. libperfmgr remains the sole shared node writer.
    void apply(int level, long until) {
        if (level == NONE && mVote.level() == NONE) return;
        try {
            boolean reconnect = mBoost == null;
            if (reconnect) {
                IBinder binder = ServiceManager.checkService(HAL_SERVICE);
                if (binder == null) return;
                IPowerExt hal = IPowerExt.Stub.asInterface(binder.getExtension());
                if (hal == null) return;
                mBoost = hal::setBoost;
            }
            mVote.apply(mBoost, level, until, reconnect);
        } catch (RemoteException e) {
            mBoost = null;
            Slog.w(TAG, "Power HAL extension unavailable", e);
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  performanceMask=0x" + Long.toHexString(performanceMask));
        pw.println("  hint=" + mVote.hint() + " expiresUptime=" + mVote.until()
                + " halConnected=" + (mBoost != null));
    }
}
