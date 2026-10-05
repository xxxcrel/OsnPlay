package com.shilapi.xcertplay.media

import android.Manifest
import com.shilapi.xcertplay.airplay.AirPlayCrypto
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import com.shilapi.xcertplay.airplay.MicrophoneSource
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MicrophoneUplinkTransportTest {
    @Test fun standardMicSendsAnAuthenticatedPcmFrameOnTheSelectedLocalLink() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val loopback = InetAddress.getLoopbackAddress()
        DatagramSocket(0, loopback).use { receiver ->
            receiver.soTimeout = 5_000
            val key = ByteArray(32) { 3 }
            val pcm = ByteArray(640) { if (it % 2 == 0) 0x34 else 0x12 }
            val delivered = AtomicBoolean()
            ShadowAudioRecord.setSourceProvider {
                object : ShadowAudioRecord.AudioRecordSource {
                    override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                        if (delivered.compareAndSet(false, true)) {
                            pcm.copyInto(buffer, offset)
                            return pcm.size
                        }
                        Thread.sleep(5)
                        return 0
                    }
                }
            }
            val uplink = MicrophoneUplink(MicrophoneConfig("speechrecognition", 16_000, 1, 100, 20,
                loopback, receiver.localPort, key, captureSource = MicrophoneSource.MIC, bindAddress = loopback))
            try {
                assertTrue(uplink.start())
                val packet = DatagramPacket(ByteArray(2048), 2048)
                receiver.receive(packet)
                assertEquals(loopback, packet.address)
                val wire = packet.data.copyOf(packet.length)
                val nonce = ByteArray(12).also { wire.copyInto(it, 4, wire.size - 8, wire.size) }
                val decoded = AirPlayCrypto.chachaOpen(key, nonce, wire.copyOfRange(12, wire.size - 8), wire.copyOfRange(4, 12))
                assertArrayEquals(MicrophonePacketizer.toWirePcm(pcm), decoded)
            } finally { uplink.close(); ShadowAudioRecord.clearSource() }
        }
    }
}
