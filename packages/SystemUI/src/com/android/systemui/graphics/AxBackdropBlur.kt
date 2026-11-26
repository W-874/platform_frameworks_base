/*
 * Copyright (C) 2025-2026 AxionOS
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

package com.android.systemui.graphics

import android.graphics.Canvas
import android.view.View
import androidx.annotation.UiThread
import com.android.internal.graphics.drawable.BackgroundBlurDrawable

/**
 * A single cross-window region, adapted from Axion's BlurEngine. The caller supplies the visual
 * policy and current WindowManager capability, and releases the region on detach or invisibility.
 * SurfaceFlinger chooses the blur algorithm; this never captures or re-renders the view hierarchy.
 */
@UiThread
class AxBackdropBlur {
    var drawable: BackgroundBlurDrawable? = null
        private set

    /** Returns null when the caller should draw its normal background instead. */
    fun acquire(host: View, canvas: Canvas, radiusPx: Int): BackgroundBlurDrawable? {
        if (!canvas.isHardwareAccelerated || radiusPx <= 0) return null
        val root = host.viewRootImpl ?: return null
        return drawable ?: root.createBackgroundBlurDrawable().also {
            it.setBlurRadius(radiusPx.coerceAtMost(MAX_RADIUS_PX))
            it.callback = host
            drawable = it
        }
    }

    /** Deactivate the registered region before dropping its reference. */
    fun release() {
        val region = drawable ?: return
        drawable = null
        region.callback = null
        region.setVisible(false, false)
        region.setBlurRadius(0)
    }

    private companion object {
        const val MAX_RADIUS_PX = 120
    }
}
