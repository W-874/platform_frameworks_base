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

package com.android.systemui.graphics

import android.graphics.Canvas
import android.view.View
import android.view.ViewRootImpl
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.graphics.drawable.BackgroundBlurDrawable
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class AxBackdropBlurTest {
    private val host = mock(View::class.java)
    private val canvas = mock(Canvas::class.java)
    private val blur = AxBackdropBlur()

    @Test
    fun softwareCanvasDoesNotAcquireRegion() {
        assertThat(blur.acquire(host, canvas, 50)).isNull()
        verifyNoInteractions(host)
    }

    @Test
    fun unattachedHardwareViewDoesNotAcquireRegion() {
        whenever(canvas.isHardwareAccelerated).thenReturn(true)
        assertThat(blur.acquire(host, canvas, 50)).isNull()
    }

    @Test
    fun regionIsCappedReusedAndDeactivatedOnRelease() {
        val root = mock(ViewRootImpl::class.java)
        val region = mock(BackgroundBlurDrawable::class.java)
        whenever(canvas.isHardwareAccelerated).thenReturn(true)
        whenever(host.viewRootImpl).thenReturn(root)
        whenever(root.createBackgroundBlurDrawable()).thenReturn(region)

        assertThat(blur.acquire(host, canvas, 500)).isSameInstanceAs(region)
        assertThat(blur.acquire(host, canvas, 500)).isSameInstanceAs(region)
        verify(root).createBackgroundBlurDrawable()
        verify(region).setBlurRadius(120)

        blur.release()
        assertThat(blur.drawable).isNull()
        verify(region).setVisible(false, false)
        verify(region).setBlurRadius(0)
        verify(region).callback = null
    }
}
