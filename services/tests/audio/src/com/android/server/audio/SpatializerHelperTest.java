/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.audio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.os.PowerManager;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class SpatializerHelperTest {

    private static final String TAG = "SpatializerHelperTest";

    private static final AudioDeviceAttributes A2DP_DEVICE =
            new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, "00:11:22:33:44:55");

    // the actual class under test
    private SpatializerHelper mSpatHelper;

    @Mock private AudioService mMockAudioService;
    @Mock private PowerManager.WakeLock mMockWakelock;
    @Spy private AudioSystemAdapter mSpyAudioSystem;
    @Spy private AudioDeviceBroker mSpyDeviceBroker;

    @Before
    public void setUp() throws Exception {
        mMockAudioService = mock(AudioService.class);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mSpyAudioSystem = spy(new NoOpAudioSystemAdapter());
        mSpyDeviceBroker = spy(new AudioDeviceBroker(context, mMockAudioService,
                new AudioDeviceInventory(mSpyAudioSystem,
                        mSpyAudioSystem.getAudioProductStrategies(/* filterInternal= */ true)),
                SystemServerAdapter.getDefaultAdapter(context), mSpyAudioSystem, mMockWakelock));

        mSpatHelper = new SpatializerHelper(mMockAudioService, mSpyAudioSystem,
                mSpyDeviceBroker, /*binauralEnabledDefault=*/true, /*transauralEnabledDefault=*/
                true, /*headTrackingEnabledDefault*/false);
    }

    @Test
    public void testAdiDeviceStateSettings() throws Exception {
        Log.i(TAG, "starting testSADeviceSettings");
        final AudioDeviceAttributes dev1 =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_SPEAKER, "");
        final AudioDeviceAttributes dev2 =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, "C3:PO:beep");
        final AudioDeviceAttributes dev3 =
                new AudioDeviceAttributes(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP, "R2:D2:bloop");

        doNothing().when(mSpyDeviceBroker).postPersistAudioDeviceSettings();
        mSpatHelper.initForTest(true /*binaural*/, true /*transaural*/);

        // test with single device
        mSpatHelper.addCompatibleAudioDevice(dev1);
        checkAddSettings();

        // test with 2+ devices so separator character is used in list
        mSpatHelper.addCompatibleAudioDevice(dev2);
        Assert.assertTrue(mSpatHelper.isAvailableForDevice(dev2));
        checkAddSettings();
        Assert.assertTrue(mSpatHelper.isAvailableForDevice(dev2));
        mSpatHelper.addCompatibleAudioDevice(dev3);
        checkAddSettings();

        // test adding a device twice in the list
        mSpatHelper.addCompatibleAudioDevice(dev1);
        checkAddSettings();

        // test removing a device
        mSpatHelper.removeCompatibleAudioDevice(dev2);
        // spatializer could still be run for dev2 (is available) but spatial audio
        // is disabled for dev2 by removeCompatibleAudioDevice
        Assert.assertTrue(mSpatHelper.isAvailableForDevice(dev2));
        List<AudioDeviceAttributes> compatDevices = mSpatHelper.getCompatibleAudioDevices();
        Assert.assertFalse(compatDevices.stream().anyMatch(dev -> dev.equalTypeAddress(dev2)));
        checkAddSettings();
    }

    @Test
    public void refreshDeviceWithoutHeadTracker_skipsHeadTrackerRequest() {
        addA2dpDeviceState();
        SpatializerHelper helper = spy(mSpatHelper);

        helper.refreshDevice(A2DP_DEVICE, /*initState=*/true);

        Assert.assertTrue(helper.isAvailableForDevice(A2DP_DEVICE));
        verify(helper, never()).setHeadTrackerEnabled(anyBoolean(), any());
        verify(mSpyDeviceBroker, never()).postPersistAudioDeviceSettings();
    }

    @Test
    public void refreshDeviceWithHeadTracker_preservesEnabledPreference() {
        AdiDeviceState deviceState = addA2dpDeviceState();
        deviceState.setHasHeadTracker(true);
        deviceState.setHeadTrackerEnabled(true);

        mSpatHelper.refreshDevice(A2DP_DEVICE, /*initState=*/true);

        Assert.assertTrue(deviceState.isHeadTrackerEnabled());
        verify(mSpyDeviceBroker, never()).postPersistAudioDeviceSettings();
    }

    @Test
    public void setHeadTrackerEnabledWithoutHeadTracker_leavesPreferenceUnchanged() {
        AdiDeviceState deviceState = addA2dpDeviceState();

        mSpatHelper.setHeadTrackerEnabled(true, A2DP_DEVICE);
        Assert.assertFalse(deviceState.isHeadTrackerEnabled());

        deviceState.setHeadTrackerEnabled(true);
        mSpatHelper.setHeadTrackerEnabled(false, A2DP_DEVICE);
        Assert.assertTrue(deviceState.isHeadTrackerEnabled());
        Assert.assertFalse(deviceState.hasHeadTracker());
        verify(mSpyDeviceBroker, never()).postPersistAudioDeviceSettings();
    }

    private AdiDeviceState addA2dpDeviceState() {
        mSpatHelper.initForTest(/*hasBinaural=*/true, /*hasTransaural=*/true);
        AdiDeviceState deviceState = new AdiDeviceState(A2DP_DEVICE.getType(),
                A2DP_DEVICE.getInternalType(), A2DP_DEVICE.getAddress());
        mSpyDeviceBroker.addOrUpdateDeviceSAStateInInventory(deviceState, /*syncInventory=*/false);
        return deviceState;
    }

    /**
     * Gets the string representing the current configuration of the devices, then clears it
     * and restores the configuration. Verify the new string from the restored settings matches
     * the original one.
     */
    private void checkAddSettings() throws Exception {
        String settings = mSpyDeviceBroker.getDeviceSettings();
        Log.i(TAG, "device settings: " + settings);
        mSpyDeviceBroker.clearDeviceInventory();
        mSpyDeviceBroker.setDeviceSettings(settings, true /*binauralEnabledDefault*/,
                true /*transauralEnabledDefault*/);
        String settingsRestored = mSpyDeviceBroker.getDeviceSettings();
        Log.i(TAG, "device settingsRestored: " + settingsRestored);
        Assert.assertEquals(settings, settingsRestored);
    }

    /**
     * Test that null devices for routing do not break canBeSpatialized
     * @throws Exception
     */
    @Test
    public void testNoRoutingCanBeSpatialized() throws Exception {
        Log.i(TAG, "Starting testNoRoutingCanBeSpatialized");
        mSpatHelper.forceStateForTest(SpatializerHelper.STATE_ENABLED_AVAILABLE);

        final ArrayList<AudioDeviceAttributes> emptyList = new ArrayList<>(0);
        final ArrayList<AudioDeviceAttributes> listWithNull = new ArrayList<>(1);
        listWithNull.add(null);
        final AudioAttributes media = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();
        final AudioFormat spatialFormat = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build();

        when(mSpyAudioSystem.getDevicesForAttributes(any(AudioAttributes.class), anyBoolean()))
                .thenReturn(emptyList);
        Assert.assertFalse("can be spatialized on empty routing",
                mSpatHelper.canBeSpatialized(media, spatialFormat));

        when(mSpyAudioSystem.getDevicesForAttributes(any(AudioAttributes.class), anyBoolean()))
                .thenReturn(listWithNull);
        Assert.assertFalse("can be spatialized on null routing",
                mSpatHelper.canBeSpatialized(media, spatialFormat));
    }
}
