package com.example.whisperserver

import com.example.whisperserver.network.TailscaleDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleDetectorTest {

    @Test
    fun recognizesTailscaleCgnatRange() {
        assertTrue(TailscaleDetector.isTailscaleAddress("100.64.0.1"))
        assertTrue(TailscaleDetector.isTailscaleAddress("100.100.50.25"))
        assertTrue(TailscaleDetector.isTailscaleAddress("100.127.255.254"))
    }

    @Test
    fun rejectsAddressesOutsideCgnatRange() {
        // 100.0-63 and 100.128-255 are NOT in 100.64.0.0/10.
        assertFalse(TailscaleDetector.isTailscaleAddress("100.63.0.1"))
        assertFalse(TailscaleDetector.isTailscaleAddress("100.128.0.1"))
        assertFalse(TailscaleDetector.isTailscaleAddress("192.168.1.10"))
        assertFalse(TailscaleDetector.isTailscaleAddress("10.0.0.1"))
        assertFalse(TailscaleDetector.isTailscaleAddress("not-an-ip"))
        assertFalse(TailscaleDetector.isTailscaleAddress("100.64"))
    }

    @Test
    fun labelForWildcardAndTailscale() {
        assertTrue(TailscaleDetector.labelFor("0.0.0.0").contains("All interfaces"))
        assertTrue(TailscaleDetector.labelFor("100.100.1.1").contains("Tailscale"))
    }
}
