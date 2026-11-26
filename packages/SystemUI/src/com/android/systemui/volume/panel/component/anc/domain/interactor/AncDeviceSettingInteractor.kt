/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.volume.panel.component.anc.domain.interactor

import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingRepository
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingModel
import com.android.systemui.volume.domain.interactor.AudioOutputInteractor
import com.android.systemui.volume.domain.model.AudioOutputDevice
import com.android.systemui.volume.panel.dagger.scope.VolumePanelScope
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

@VolumePanelScope
class AncDeviceSettingInteractor
@Inject
constructor(
    audioOutputInteractor: AudioOutputInteractor,
    private val deviceSettingRepository: DeviceSettingRepository,
    @VolumePanelScope scope: CoroutineScope,
) {

    private val currentOutput = audioOutputInteractor.currentAudioDevice

    fun getSetting(): StateFlow<DeviceSettingModel?> = setting

    private val setting: StateFlow<DeviceSettingModel?> =
        currentOutput
            .map { (it as? AudioOutputDevice.Bluetooth)?.cachedBluetoothDevice }
            .distinctUntilChanged()
            .flatMapLatest { device ->
                if (device == null) flowOf(null) else getSettingForDevice(device)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), null)

    private fun getSettingForDevice(device: CachedBluetoothDevice): Flow<DeviceSettingModel?> {
        return deviceSettingRepository
            .getDeviceSetting(device, DeviceSettingId.DEVICE_SETTING_ID_ANC)
            .map { model ->
                if (model?.cachedDevice != device) return@map null
                when (model) {
                    is DeviceSettingModel.MultiTogglePreference ->
                        model.takeIf { it.state.selectedIndex in it.toggles.indices }?.copy(
                            updateState = { state ->
                                val current = setting.value as? DeviceSettingModel.MultiTogglePreference
                                if (
                                    isCurrentOutput(device) &&
                                        current != null &&
                                        current.cachedDevice == device &&
                                        current.isAllowedChangingState &&
                                        current.toggles == model.toggles &&
                                        state.selectedIndex in model.toggles.indices
                                ) {
                                    model.updateState(state)
                                }
                            }
                        )
                    is DeviceSettingModel.ActionSwitchPreference ->
                        model.takeIf { it.switchState != null }?.copy(
                            updateState = { state ->
                                val current = setting.value as? DeviceSettingModel.ActionSwitchPreference
                                if (
                                    isCurrentOutput(device) &&
                                        current != null &&
                                        current.cachedDevice == device &&
                                        current.isAllowedChangingState &&
                                        current.switchState == model.switchState
                                ) {
                                    model.updateState?.invoke(state)
                                }
                            }
                        )
                    else -> null
                }
            }
            .onStart { emit(null) }
    }

    private fun isCurrentOutput(device: CachedBluetoothDevice): Boolean =
        (currentOutput.value as? AudioOutputDevice.Bluetooth)?.cachedBluetoothDevice == device
}
