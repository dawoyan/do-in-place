package com.davoyans.doinplace.ui.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QrDisplayWindowPolicyTest {

    @Test
    fun cardDisplayMode_usesMaxBrightnessAndKeepsScreenOn() {
        val original = QrDisplayWindowState(
            screenBrightness = -1.0f,
            keepScreenOn = false
        )

        val display = QrDisplayWindowPolicy.displayState(original)

        assertEquals(1.0f, display.screenBrightness)
        assertTrue(display.keepScreenOn)
    }

    @Test
    fun connectionQrDisplay_restoreStateReturnsOriginalWindowSettings() {
        val original = QrDisplayWindowState(
            screenBrightness = 0.35f,
            keepScreenOn = false
        )

        val restored = QrDisplayWindowPolicy.restoreState(original)

        assertEquals(0.35f, restored.screenBrightness)
        assertFalse(restored.keepScreenOn)
    }
}
