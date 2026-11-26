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
import java.util.function.Consumer;

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
    static final String GOVERNOR = "ax_dragonite_cpu_governor";
    static final String CPU_PROFILE = "ax_dragonite_cpu_profile";
    static final String GPU_PROFILE = "ax_dragonite_gpu_profile";
    private static final String CPU_PERFORMANCE = "AX_DRAGONITE_CPU_PERFORMANCE";
    private static final String GPU_PERFORMANCE = "AX_DRAGONITE_GPU_PERFORMANCE";
    private static final String GOVERNOR_PERFORMANCE = "AX_DRAGONITE_GOVERNOR_PERFORMANCE";
    private static final String GOVERNOR_POWERSAVE = "AX_DRAGONITE_GOVERNOR_POWERSAVE";
    private static final String[] MODES = {
        CPU_PERFORMANCE, GPU_PERFORMANCE, GOVERNOR_PERFORMANCE, GOVERNOR_POWERSAVE
    };

    private static final class ProductPolicy {
        static final File FILE = new File(Environment.getProductDirectory(), "etc/ax_dragonite.xml");
    }

    record Scene(int level, int durationMs, boolean placeUi, boolean boostRendering) {}

    final SparseArray<Scene> scenes = new SparseArray<>();
    private final AxPowerVote mCpuVote = new AxPowerVote(SystemClock::uptimeMillis);
    private final AxPowerVote mGpuVote = new AxPowerVote(SystemClock::uptimeMillis);
    final long performanceMask;
    private boolean mHasRenderingScenes;
    private boolean mRenderingSupported;
    private IPowerExt mHal;
    private AxPowerVote.Boost mBoost;
    private IBinder.DeathRecipient mDeathRecipient;
    private int mSupportedModes;
    private int mActiveModes;
    private int mRequestedModes;

    void setProfiles(String governor, String cpu, String gpu, boolean enabled) {
        mRequestedModes = 0;
        if (!enabled) return;
        if ("performance".equals(cpu)) mRequestedModes |= 1;
        if ("performance".equals(gpu)) mRequestedModes |= 1 << 1;
        if ("performance".equals(governor)) mRequestedModes |= 1 << 2;
        if ("powersave".equals(governor)) mRequestedModes |= 1 << 3;
    }

    void onHalDied(IBinder binder) {
        if (mHal != null && mHal.asBinder() == binder) disconnect();
    }

    private void disconnect() {
        if (mHal != null) mHal.asBinder().unlinkToDeath(mDeathRecipient, 0);
        mHal = null;
        mBoost = null;
        mRenderingSupported = false;
    }

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
    boolean apply(int level, long until, long gpuUntil,
            Consumer<IBinder> onHalDied) {
        try {
            boolean reconnect = mHal == null;
            if (reconnect) {
                IBinder binder = ServiceManager.checkService(HAL_SERVICE);
                if (binder == null) return false;
                IPowerExt hal = IPowerExt.Stub.asInterface(binder.getExtension());
                if (hal == null) return false;
                int supported = 0;
                for (int i = 0; i < MODES.length; i++) {
                    if (hal.isModeSupported(MODES[i])) supported |= 1 << i;
                }
                boolean rendering = mHasRenderingScenes && hal.isBoostSupported(GPU_RENDERING_HINT);
                IBinder extension = hal.asBinder();
                IBinder.DeathRecipient recipient = () -> onHalDied.accept(extension);
                extension.linkToDeath(recipient, 0);
                mHal = hal;
                mBoost = hal::setBoost;
                mDeathRecipient = recipient;
                mSupportedModes = supported;
                mRenderingSupported = rendering;
            }
            final int requested = mRequestedModes & mSupportedModes;
            final int previous = reconnect ? mSupportedModes & ~requested : mActiveModes;
            // Enable replacements first so governor changes need not visit schedutil in between.
            for (int i = 0; i < MODES.length; i++) {
                final int bit = 1 << i;
                if ((requested & bit) != 0 && (reconnect || (previous & bit) == 0)) {
                    mHal.setMode(MODES[i], true);
                }
            }
            for (int i = 0; i < MODES.length; i++) {
                final int bit = 1 << i;
                if ((previous & bit) != 0 && (requested & bit) == 0) {
                    mHal.setMode(MODES[i], false);
                }
            }
            mActiveModes = requested;
            mCpuVote.apply(mBoost, CPU_HINTS[level], until, reconnect);
            mGpuVote.apply(mBoost,
                    mRenderingSupported && gpuUntil != 0 ? GPU_RENDERING_HINT : null,
                    gpuUntil, reconnect);
            return true;
        } catch (RemoteException e) {
            Slog.w(TAG, "Power HAL extension unavailable", e);
            disconnect();
            return false;
        }
    }

    void dump(PrintWriter pw) {
        pw.println("  performanceMask=0x" + Long.toHexString(performanceMask));
        pw.println("  hint=" + mCpuVote.hint() + " expiresUptime=" + mCpuVote.until()
                + " halConnected=" + (mHal != null));
        pw.println("  gpuHint=" + mGpuVote.hint() + " expiresUptime=" + mGpuVote.until()
                + " renderingSupported=" + mRenderingSupported);
        for (int i = 0; i < MODES.length; i++) {
            int bit = 1 << i;
            pw.println("  " + MODES[i] + " requested=" + ((mRequestedModes & bit) != 0)
                    + " supported=" + ((mSupportedModes & bit) != 0)
                    + " active=" + (mHal != null && (mActiveModes & bit) != 0));
        }
    }
}
