/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import static org.junit.Assert.assertEquals;

import android.util.Xml;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.StringReader;
import java.util.List;

public class AxKernelConfigLoaderTest {
    private static final String POLICY0 = "/sys/devices/system/cpu/cpufreq/policy0";
    private static final String POLICY4 = "/sys/devices/system/cpu/cpufreq/policy4";

    @Test
    public void onlyOptedInCpuPolicySuppliesPlacement() throws Exception {
        assertEquals(List.of(POLICY4), parse("""
                <kernel-manager>
                    <cpu id="little" path="%s" />
                    <cpu id="big" path="%s" uiPlacement="true" />
                    <gpu node="/sys/class/kgsl/kgsl-3d0" uiPlacement="true" />
                </kernel-manager>
                """.formatted(POLICY0, POLICY4)));
    }

    @Test
    public void upstreamNodeAttributeTakesPrecedenceOverPath() throws Exception {
        assertEquals(List.of(POLICY4), parse("""
                <kernel-manager>
                    <cpu node="%s" path="%s" uiPlacement="true" />
                </kernel-manager>
                """.formatted(POLICY4, POLICY0)));
    }

    @Test(expected = XmlPullParserException.class)
    public void aSelectedPathMustBeACpufreqPolicy() throws Exception {
        parse("""
                <kernel-manager>
                    <cpu path="/sys/devices/system/cpu/cpufreq/../" uiPlacement="true" />
                </kernel-manager>
                """);
    }

    @Test(expected = XmlPullParserException.class)
    public void rejectsDuplicatePlacementPolicy() throws Exception {
        parse("""
                <kernel-manager>
                    <cpu path="%s" uiPlacement="true" />
                    <cpu node="%s" uiPlacement="true" />
                </kernel-manager>
                """.formatted(POLICY4, POLICY4));
    }

    @Test
    public void parseKernelRelatedCpusMask() {
        assertEquals(0xf0L, AxKernelConfigLoader.parseRelatedCpus("4 5 6 7\n"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsCpuOutsideAffinityMask() {
        AxKernelConfigLoader.parseRelatedCpus("4 64\n");
    }

    private static List<String> parse(String xml) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        return AxKernelConfigLoader.readPlacementPolicies(parser);
    }
}
