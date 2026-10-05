package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class CarPlayAudioStateTest {
    @Test fun phoneMetadataOrVoiceDoesNotCountAsReceivingCarPlayMusic() {
        val state = CarPlayAudioState()
        state.accepted(100, "telephony"); state.packet(100, "telephony", 100)
        assertTrue(state.report(true).contains("mediaSetups=0 mediaPackets=0 mediaBytes=0"))
        state.accepted(102, "media"); state.packet(102, "media", 256)
        assertTrue(state.report(true).contains("mediaSetups=1 mediaPackets=1 mediaBytes=256"))
    }
    @Test fun ownershipDiagnosticsNeverExposeArbitraryPeerValues() {
        val state = CarPlayAudioState()
        state.modes(mapOf("resources" to listOf(mapOf("resourceID" to 2, "owner" to "private peer name", "borrower" to 1))))
        assertTrue(state.report(true).contains("owner=other borrower=1"))
        assertFalse(state.report(true).contains("private peer name"))
    }
    @Test fun musicOutputAndLatencyProfilesMatchForBothEntertainmentRates() {
        for (rate in listOf(44100, 48000)) {
            val info = AirPlayInfoPlist.build(AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:01", "1",
                AirPlayDisplayConfig(1280, 720), entertainmentSampleRate = rate))
            val formats = (info["audioFormats"] as List<*>).filterIsInstance<Map<*, *>>()
            val latencies = (info["audioLatencies"] as List<*>).filterIsInstance<Map<*, *>>()
            formats.filter { it["audioType"] == "media" }.forEach { format ->
                assertTrue(latencies.any { it["type"] == format["type"] && it["audioType"] == "media" })
            }
        }
    }
}
