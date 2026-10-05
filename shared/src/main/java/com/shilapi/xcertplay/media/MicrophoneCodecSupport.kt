package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/** Android 9 has no guaranteed Opus encoder; use the supported PCM uplink on that platform. */
object MicrophoneCodecSupport {
    fun opusAvailable(): Boolean = Build.VERSION.SDK_INT >= 29 && runCatching {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 1)
            .apply { setInteger(MediaFormat.KEY_BIT_RATE, 48_000) }
        MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) != null
    }.getOrDefault(false)
}
