/*
 * Copyright (C) 2025-2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.os.Environment;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Read-only CPU topology subset of Axion's kernel-manager configuration. */
final class AxKernelConfigLoader {
    private static final String TAG = "AxKernelConfigLoader";
    private static final Pattern CPU_POLICY = Pattern.compile(
            "/sys/devices/system/cpu/cpufreq/policy[0-9]+");

    private AxKernelConfigLoader() {}

    static long loadPlacementMask() {
        File config = Environment.buildPath(Environment.getSystemExtDirectory(),
                "etc", "ax_kernel_manager.xml");
        if (!config.isFile()) return 0;
        try (FileInputStream input = new FileInputStream(config)) {
            long mask = 0;
            for (String path : readPlacementPolicies(Xml.resolvePullParser(input))) {
                try (FileInputStream cpus = new FileInputStream(path + "/related_cpus")) {
                    mask |= parseRelatedCpus(new String(cpus.readNBytes(256),
                            StandardCharsets.US_ASCII));
                }
            }
            return mask;
        } catch (IOException | XmlPullParserException | IllegalArgumentException e) {
            Slog.w(TAG, "CPU topology unavailable; leaving thread placement to Android", e);
            return 0;
        }
    }

    static List<String> readPlacementPolicies(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        XmlUtils.beginDocument(parser, "kernel-manager");
        List<String> policies = new ArrayList<>();
        final int depth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, depth)) {
            if (!"cpu".equals(parser.getName())
                    || !"true".equals(parser.getAttributeValue(null, "uiPlacement"))) continue;
            // Axion prefers the node attribute but accepts path in device configurations.
            String node = parser.getAttributeValue(null, "node");
            if (node == null || node.isEmpty()) node = parser.getAttributeValue(null, "path");
            if (node == null || !CPU_POLICY.matcher(node).matches() || policies.contains(node)) {
                throw new XmlPullParserException("Invalid or duplicate placement CPU policy: " + node,
                        parser, null);
            }
            policies.add(node);
        }
        return policies;
    }

    static long parseRelatedCpus(String contents) {
        long mask = 0;
        for (String token : contents.trim().split("\\s+")) {
            int cpu = Integer.parseInt(token);
            if (cpu < 0 || cpu >= Long.SIZE) {
                throw new IllegalArgumentException("CPU outside affinity mask: " + cpu);
            }
            mask |= 1L << cpu;
        }
        return mask;
    }
}
