package com.example.whisperserver

import com.example.whisperserver.ui.screens.formatRate
import org.junit.Assert.assertEquals
import org.junit.Test

class FormattersTest {

    @Test
    fun rateFormatsTwoDecimalsByDefault() {
        assertEquals("3.96×", formatRate(3.96))
    }

    @Test
    fun rateHonorsDecimals() {
        assertEquals("3.9×", formatRate(3.94, decimals = 1))
        assertEquals("4×", formatRate(4.0, decimals = 0))
    }

    @Test
    fun rateDashForNullOrNonPositive() {
        assertEquals("—", formatRate(null))
        assertEquals("—", formatRate(0.0))
        assertEquals("—", formatRate(-1.0))
    }
}
