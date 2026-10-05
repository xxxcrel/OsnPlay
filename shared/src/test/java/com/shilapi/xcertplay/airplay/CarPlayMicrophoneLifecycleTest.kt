package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CarPlayMicrophoneLifecycleTest {
    private class Sink : MediaSink {
        val starts = mutableListOf<MicrophoneConfig>()
        val stops = mutableListOf<AudioStreamId>()
        override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) { starts += config }
        override fun onMicrophoneStopped(id: AudioStreamId) { stops += id }
    }

    @Test fun uplinkOnlyVoiceStartsAfterSetupWithoutWaitingForAnyDownlinkPacket() {
        val sink = Sink()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val session = session()
        try {
            assertNotNull(engine.onAudio(session, 100, setup("speechRecognition")))
            assertTrue(sink.starts.isEmpty())
            engine.onSetupResponseSent(session)
            assertEquals(1, sink.starts.size)
            assertEquals("speechrecognition", sink.starts.single().audioType)
            assertEquals(InetAddress.getLoopbackAddress(), sink.starts.single().bindAddress)
            engine.onSetupResponseSent(session)
            assertEquals(1, sink.starts.size)
            engine.onSessionClosed(session)
            assertEquals(listOf(AudioStreamId(100, "speechrecognition")), sink.stops)
            engine.onSetupResponseSent(session)
            assertEquals(1, sink.starts.size)
        } finally { engine.onSessionClosed(session); session.close() }
    }

    @Test fun microphonePermissionAndAuthenticationAreRequiredBeforeCapture() {
        for ((enabled, verified) in listOf(false to true, true to false)) {
            val sink = Sink()
            val engine = CarPlayMediaEngine(sink, microphoneEnabled = enabled)
            val session = session(verified)
            try {
                engine.onAudio(session, 100, setup("telephony"))
                engine.onSetupResponseSent(session)
                assertTrue(sink.starts.isEmpty())
            } finally { engine.onSessionClosed(session); session.close() }
        }
    }

    @Test fun teardownBeforeSetupCommitCannotStartAStaleMicrophone() {
        val sink = Sink()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val session = session()
        try {
            engine.onAudio(session, 100, setup("telephony"))
            engine.onTeardown(session, 100)
            engine.onSetupResponseSent(session)
            assertTrue(sink.starts.isEmpty())
            assertTrue(sink.stops.isEmpty())
        } finally { engine.onSessionClosed(session); session.close() }
    }

    @Test fun defaultInputUsesTheAdvertisedMicrophoneButMusicDoesNot() {
        val sink = Sink()
        val engine = CarPlayMediaEngine(sink, microphoneEnabled = true)
        val session = session()
        try {
            engine.onAudio(session, 100, setup("default"))
            engine.onAudio(session, 100, setup("media"))
            engine.onSetupResponseSent(session)
            assertEquals(listOf("default"), sink.starts.map { it.audioType })
            engine.onTeardown(session, 100)
            assertEquals(listOf(AudioStreamId(100, "default")), sink.stops)
        } finally { engine.onSessionClosed(session); session.close() }
    }

    private fun setup(type: String) = mapOf("audioType" to type, "audioFormat" to 0x10L,
        "streamConnectionID" to 42L, "dataPort" to 54321L, "framesPerPacket" to 320L)

    private fun session(verified: Boolean = true): AirPlaySession {
        val loopback = InetAddress.getLoopbackAddress()
        val socket = object : Socket() {
            override fun getInetAddress(): InetAddress = loopback
            override fun getLocalAddress(): InetAddress = loopback
            override fun getRemoteSocketAddress(): SocketAddress = InetSocketAddress(loopback, 7000)
        }
        val session = AirPlaySession(socket, AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
            AirPlayDisplayConfig(800, 480)), AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, ByteArray(32) { 1 })
        session.pairVerify.javaClass.getDeclaredField("verified").apply { isAccessible = true }
            .setBoolean(session.pairVerify, verified)
        return session
    }
}
