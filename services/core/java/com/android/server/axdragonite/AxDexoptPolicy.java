/*
 * Copyright (C) 2025-2026 AxionOS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.server.axdragonite;

import android.content.Context;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Slog;

import com.android.server.art.ArtManagerLocal;
import com.android.server.art.ReasonMapping;

/** Axion's in-call background dexopt guard, using the existing ART job and cancellation. */
public final class AxDexoptPolicy {
    private static final String TAG = "AxDexoptPolicy";
    private static final String ENABLED = "ax_dexopt_guard_enabled";

    private AxDexoptPolicy() {}

    public static void register(Context context, ArtManagerLocal art) {
        if (!AxDragoniteService.isConfigured()) return;
        art.setBatchDexoptStartCallback(Runnable::run,
                (snapshot, reason, packages, params, cancellation) -> {
                    if (!ReasonMapping.REASON_BG_DEXOPT.equals(reason)
                            || Settings.Global.getInt(context.getContentResolver(), ENABLED, 1) == 0) {
                        return;
                    }
                    TelecomManager telecom = context.getSystemService(TelecomManager.class);
                    if (telecom != null && telecom.isInCall()) {
                        Slog.i(TAG, "Deferring background dexopt during a call");
                        cancellation.cancel();
                    }
                });
    }
}
