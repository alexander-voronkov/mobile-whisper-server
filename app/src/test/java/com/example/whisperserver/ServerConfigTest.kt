package com.example.whisperserver

import com.example.whisperserver.data.ServerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {

    @Test
    fun defaultsMatchSpec() {
        val config = ServerConfig()
        assertEquals("0.0.0.0", config.host)
        assertEquals(8080, config.port)
        assertEquals("auto", config.language)
        assertEquals(2, config.threads)
        assertFalse(config.translate)
        // Convert is off by default: ffmpeg is not bundled, and --convert would
        // make whisper-server exit at startup.
        assertFalse(config.convertAudio)
        assertFalse(config.vad)
        assertFalse(config.autostart)
    }

    @Test
    fun portValidationRange() {
        assertFalse(ServerConfig.isValidPort(1023))
        assertTrue(ServerConfig.isValidPort(1024))
        assertTrue(ServerConfig.isValidPort(8080))
        assertTrue(ServerConfig.isValidPort(65535))
        assertFalse(ServerConfig.isValidPort(65536))
        assertFalse(ServerConfig.isValidPort(0))
        assertFalse(ServerConfig.isValidPort(-1))
    }

    @Test
    fun selectedModelResolves() {
        val config = ServerConfig(selectedModelId = "base.en")
        assertEquals("base.en", config.selectedModel?.id)
    }
}
