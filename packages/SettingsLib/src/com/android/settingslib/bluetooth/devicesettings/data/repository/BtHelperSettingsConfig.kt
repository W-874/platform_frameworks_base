/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.settingslib.bluetooth.devicesettings.data.repository

import com.android.settingslib.bluetooth.devicesettings.DeviceSettingId
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingItem
import com.android.settingslib.bluetooth.devicesettings.DeviceSettingsConfig
import com.android.settingslib.bluetooth.devicesettings.data.repository.DeviceSettingServiceConnection.EndPoint

internal const val BTHELPER_PACKAGE = "com.android.bluetooth.bthelper"
internal val BTHELPER_CONFIG_ENDPOINT = EndPoint(
    BTHELPER_PACKAGE,
    "$BTHELPER_PACKAGE.BtHelperDeviceSettingsConfigService",
    "$BTHELPER_PACKAGE.action.DEVICE_SETTINGS_CONFIG",
    false,
)
private const val BTHELPER_PROVIDER_CLASS = "$BTHELPER_PACKAGE.BtHelperDeviceSettingsProviderService"
private const val BTHELPER_PROVIDER_ACTION = "$BTHELPER_PACKAGE.action.DEVICE_SETTINGS_PROVIDER"
internal val BTHELPER_PROVIDER_ENDPOINT = EndPoint(
    BTHELPER_PACKAGE, BTHELPER_PROVIDER_CLASS, BTHELPER_PROVIDER_ACTION, false,
)

private const val PIXEL_SUPPORT_PACKAGE = "com.google.android.apps.pixel.support"
private const val PIXEL_SUPPORT_ACTION = "$PIXEL_SUPPORT_PACKAGE.bluetooth.settingsprovider.BIND_SERVICE"

// Built-in preference IDs and keys in the Fast Pair AOSP fallback shipped on sagit.
private val fastPairFallbackBuiltIns = mapOf(
    1 to "bluetooth_device_header",
    22 to "general_bluetooth_device_header",
    2 to "advanced_bluetooth_device_header",
    3 to "le_audio_bluetooth_device_header",
    4 to "hearing_aid_pair_other_button",
    5 to "hearing_aid_space_layout",
    6 to "action_buttons",
    7 to "device_stylus",
    23 to "audio_sharing_control",
    9 to "bt_device_slice_category",
    10 to "device_companion_apps",
    11 to "hearing_device_group",
    13 to "spatial_audio_group",
    14 to "bluetooth_profiles",
    12 to "bluetooth_audio_device_type_group",
    15 to "bt_extra_options",
    16 to "bluetooth_related_tools",
    17 to "data_sync_group",
    18 to "keyboard_settings",
    24 to "game_controller_settings",
    19 to "device_details_footer",
)

internal fun DeviceSettingsConfig.isGenericFastPairConfig(): Boolean =
    moreSettingsItems.isEmpty() && moreSettingsHelpItem == null &&
        mainContentItems.all { item ->
            if (item.settingId == 4001 || item.settingId == 4002) {
                item.isOptional && item.preferenceKey.isNullOrEmpty() &&
                    item.packageName == PIXEL_SUPPORT_PACKAGE &&
                    item.className.isNullOrEmpty() && item.intentAction == PIXEL_SUPPORT_ACTION
            } else {
                item.preferenceKey != null && fastPairFallbackBuiltIns[item.settingId] == item.preferenceKey &&
                    item.packageName.isNullOrEmpty() && item.className.isNullOrEmpty() &&
                    item.intentAction.isNullOrEmpty()
            }
        } && mainContentItems.distinctBy { it.settingId }.size == mainContentItems.size

internal data class BtHelperSupplement(val config: DeviceSettingsConfig, val settingIds: Set<Int>)

internal fun DeviceSettingsConfig.withBtHelperControls(helper: DeviceSettingsConfig): BtHelperSupplement? {
    if (helper.moreSettingsHelpItem != null) return null
    val helperGroups = helper.settingGroups.orEmpty()
    val groupOffset = settingGroups.orEmpty().size
    val occupied = mainContentItems.mapTo(mutableSetOf()) { it.settingId }
    val settingIds = mutableSetOf<Int>()
    val main = ArrayList<DeviceSettingItem>()
    val more = ArrayList<DeviceSettingItem>()
    var moreLink: DeviceSettingItem? = null

    for ((input, output) in listOf(helper.mainContentItems to main, helper.moreSettingsItems to more)) {
        for (item in input) {
            // Keep the GMS layout intact. BtHelper's built-ins belong to its standalone layout.
            if (!item.preferenceKey.isNullOrEmpty()) continue
            if (!occupied.add(item.settingId)) return null
            if (item.settingId == DeviceSettingId.DEVICE_SETTING_ID_MORE_SETTINGS) {
                if (output !== main || item.groupIndex != null || item.packageName != null ||
                    item.className != null || item.intentAction != null) return null
                moreLink = item
                continue
            }
            if (item.packageName != BTHELPER_PACKAGE || item.className != BTHELPER_PROVIDER_CLASS ||
                item.intentAction != BTHELPER_PROVIDER_ACTION) return null
            val group = item.groupIndex
            if (group != null && group !in helperGroups.indices) return null
            settingIds.add(item.settingId)
            output.add(if (group == null || groupOffset == 0) item else item.copy(groupIndex = group + groupOffset))
        }
    }
    if (settingIds.isEmpty()) return null
    val actionIndex = mainContentItems.indexOfFirst {
        it.settingId == DeviceSettingId.DEVICE_SETTING_ID_ACTION_BUTTONS
    }
    var mainInsertion = actionIndex + 1
    val group = mainContentItems.getOrNull(actionIndex)?.groupIndex
    if (group != null) {
        while (mainContentItems.getOrNull(mainInsertion)?.groupIndex == group) mainInsertion++
    }
    val mergedMain = buildList {
        addAll(mainContentItems.subList(0, mainInsertion))
        addAll(main)
        if (more.isNotEmpty()) moreLink?.let(::add)
        addAll(mainContentItems.subList(mainInsertion, mainContentItems.size))
    }
    return BtHelperSupplement(
        copy(mainContentItems = mergedMain, moreSettingsItems = more,
            settingGroups = settingGroups.orEmpty() + helperGroups),
        settingIds,
    )
}
