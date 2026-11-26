/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;

public class AxKernelManagerTest {
    @Test
    public void renderingProfileDoesNotChangeCpuLevelOrPlacement() throws Exception {
        AxKernelManager.Scene cpuOnly = parse("""
                <scene level="heavy" durationMs="900" />
                """);
        assertFalse(cpuOnly.boostRendering());
        assertFalse(cpuOnly.placeUi());
        assertEquals(AxKernelManager.HEAVY, cpuOnly.level());
        assertEquals(900, cpuOnly.durationMs());

        AxKernelManager.Scene scene = parse("""
                <scene level="light" durationMs="320" placeUi="true" gpuProfile="rendering" />
                """);
        assertTrue(scene.boostRendering());
        assertTrue(scene.placeUi());
        assertEquals(AxKernelManager.LIGHT, scene.level());
        assertEquals(320, scene.durationMs());
    }

    @Test(expected = XmlPullParserException.class)
    public void policyCannotInjectAnArbitraryHalHint() throws Exception {
        parse("""
                <scene level="light" durationMs="320" gpuProfile="FIXED_PERFORMANCE" />
                """);
    }

    private static AxKernelManager.Scene parse(String xml) throws Exception {
        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
        parser.setInput(new StringReader(xml));
        assertEquals(XmlPullParser.START_TAG, parser.nextTag());
        return AxKernelManager.parseScene(parser, 2);
    }
}
