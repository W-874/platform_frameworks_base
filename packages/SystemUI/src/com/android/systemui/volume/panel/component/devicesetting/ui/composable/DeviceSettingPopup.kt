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

package com.android.systemui.volume.panel.component.devicesetting.ui.composable

import android.view.Gravity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingModel
import com.android.settingslib.bluetooth.devicesettings.shared.model.DeviceSettingStateModel
import com.android.systemui.animation.Expandable
import com.android.systemui.bluetooth.devicesettings.shared.model.toSysUiIcon
import com.android.systemui.common.ui.compose.Icon
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.volume.panel.component.devicesetting.ui.viewmodel.DeviceSettingComponentViewModel
import com.android.systemui.volume.panel.component.popup.ui.composable.VolumePanelPopup
import com.android.systemui.volume.panel.component.popup.ui.composable.VolumePanelPopupDefaults
import com.android.systemui.volume.panel.component.selector.ui.composable.VolumePanelRadioButtonBar
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

class DeviceSettingPopup
@AssistedInject
constructor(
    @Assisted private val viewModelFactory: () -> DeviceSettingComponentViewModel,
    private val volumePanelPopup: VolumePanelPopup,
) {

    /** Shows a popup with the [expandable] animation. */
    fun show(
        expandable: Expandable,
        horizontalGravity: Int,
        anchor: DeviceSettingModel.MultiTogglePreference,
    ) {
        val gravity = horizontalGravity or Gravity.BOTTOM
        volumePanelPopup.show(expandable, gravity, body = { Body(it, anchor) })
    }

    @Composable
    private fun Body(dialog: SystemUIDialog, anchor: DeviceSettingModel.MultiTogglePreference) {
        val viewModel = rememberViewModel("DeviceSettingPopup#viewModel") { viewModelFactory() }
        val setting = viewModel.setting as? DeviceSettingModel.MultiTogglePreference
        if (
            setting == null || setting.cachedDevice != anchor.cachedDevice ||
                setting.id != anchor.id || !setting.isAllowedChangingState ||
                setting.state.selectedIndex !in setting.toggles.indices
        ) {
            SideEffect { dialog.dismiss() }
            return
        }

        Box(
            modifier = Modifier.padding(horizontal = 80.dp).fillMaxWidth().wrapContentHeight(),
            contentAlignment = Alignment.Center,
        ) {
            VolumePanelPopupDefaults.Title(setting.title)
        }

        Box(
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().wrapContentHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Content(setting)
        }
    }

    @Composable
    private fun Content(setting: DeviceSettingModel.MultiTogglePreference) {
        VolumePanelRadioButtonBar {
            setting.toggles.fastForEachIndexed { index, toggleModel ->
                item(
                    isSelected = index == setting.state.selectedIndex,
                    onItemSelected = {
                        setting.updateState(
                            DeviceSettingStateModel.MultiTogglePreferenceState(index)
                        )
                    },
                    contentDescription = toggleModel.label,
                    icon = {
                        Icon(icon = toggleModel.icon.toSysUiIcon(LocalContext.current, null))
                    },
                    label = {
                        Text(
                            modifier = Modifier,
                            text = toggleModel.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = LocalContentColor.current,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    },
                )
            }
        }
    }

    @AssistedFactory
    interface Factory {

        fun create(viewModelFactory: () -> DeviceSettingComponentViewModel): DeviceSettingPopup
    }
}
