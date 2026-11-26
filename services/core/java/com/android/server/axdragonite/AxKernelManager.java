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
    static final int NONE = 0;
    static final int LIGHT = 1;
    static final int HEAVY = 2;
    private static final String TAG = "AxKernelManager";
    private static final String HAL_SERVICE = "android.hardware.power.IPower/default";
    private static final String[] CPU_HINTS = {null, "AX_DRAGONITE_LIGHT", "AX_DRAGONITE_HEAVY"};
    private static final String GPU_RENDERING_HINT = "AX_DRAGONITE_GPU_RENDERING";

    private static final class ProductPolicy {
        static final File FILE = new File(Environment.getProductDirectory(), "etc/ax_dragonite.xml");
    }

    record Scene(int level, int durationMs, boolean placeUi, boolean boostRendering) {}

    final SparseArray<Scene> scenes = new SparseArray<>();
    private final AxPowerVote mCpuVote = new AxPowerVote(SystemClock::uptimeMillis);
    private final AxPowerVote mGpuVote = new AxPowerVote(SystemClock::uptimeMillis);
    final long performanceMask;
    private AxPowerVote.Boost mBoost;
    private boolean mHasRenderingScenes;
    private boolean mRenderingSupported;

    static boolean isConfigured() {
        return ProductPolicy.FILE.isFile();
    }

    private AxKernelManager(long performanceMask) {
        this.performanceMask = performanceMask;
    }

    static AxKernelManager load() throws IOException, XmlPullParserException {
        if (!isConfigured()) return null;
        try (FileInputStream input = new FileInputStream(ProductPolicy.FILE)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(input, null);
            return parse(parser, AxKernelConfigLoader.loadPlacementMask());
        }
    }

    private static AxKernelManager parse(XmlPullParser parser, long performanceMask)
            throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, "dragonite");
        AxKernelManager manager = new AxKernelManager(performanceMask);
        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (!"scene".equals(parser.getName())) {
                throw new XmlPullParserException("Expected scene rule");
            }
            int id = Integer.parseInt(parser.getAttributeValue(null, "id"));
            if (manager.scenes.get(id) != null) {
                throw new XmlPullParserException("Duplicate scene " + id);
            }
            Scene scene = parseScene(parser, id);
            manager.mHasRenderingScenes |= scene.boostRendering();
            manager.scenes.put(id, scene);
        }
        return manager;
    }

    static Scene parseScene(XmlPullParser parser, int id) throws XmlPullParserException {
        int duration = Integer.parseInt(parser.getAttributeValue(null, "durationMs"));
        int level = switch (parser.getAttributeValue(null, "level")) {
            case "light" -> LIGHT;
            case "heavy" -> HEAVY;
            default -> throw new XmlPullParserException("Invalid boost level");
        };
        if (id <= 0 || duration < 100 || duration > 1500) {
            throw new XmlPullParserException("Invalid scene " + id);
        }
        String gpuProfile = parser.getAttributeValue(null, "gpuProfile");
        if (gpuProfile != null && !"rendering".equals(gpuProfile)) {
            throw new XmlPullParserException("Invalid GPU profile for scene " + id);
        }
        return new Scene(level, duration,
                Boolean.parseBoolean(parser.getAttributeValue(null, "placeUi")), gpuProfile != null);
    }

    // Called on the Dragonite worker only. libperfmgr remains the sole shared node writer.
    void apply(int level, long until, long gpuUntil) {
        if (level == NONE && gpuUntil == 0 && mCpuVote.hint() == null && mGpuVote.hint() == null) {
            return;
        }
        try {
            boolean reconnect = mBoost == null;
            if (reconnect) {
                IBinder binder = ServiceManager.checkService(HAL_SERVICE);
                if (binder == null) return;
                IPowerExt hal = IPowerExt.Stub.asInterface(binder.getExtension());
                if (hal == null) return;
                mRenderingSupported = mHasRenderingScenes && hal.isBoostSupported(GPU_RENDERING_HINT);
                mBoost = hal::setBoost;
            }
            mCpuVote.apply(mBoost, CPU_HINTS[level], until, reconnect);
            mGpuVote.apply(mBoost, mRenderingSupported && gpuUntil != 0 ? GPU_RENDERING_HINT : null,
                    gpuUntil, reconnect);
        } catch (RemoteException e) {
            mBoost = null;
            Slog.w(TAG, "Power HAL extension unavailable", e);
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  performanceMask=0x" + Long.toHexString(performanceMask));
        pw.println("  hint=" + mCpuVote.hint() + " expiresUptime=" + mCpuVote.until()
                + " halConnected=" + (mBoost != null));
        pw.println("  gpuHint=" + mGpuVote.hint() + " expiresUptime=" + mGpuVote.until()
                + " renderingSupported=" + mRenderingSupported);
    }
}
