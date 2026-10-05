package com.shilapi.xcertplay.airplay

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Aggregate negotiation/transport evidence only; no audio or peer identifiers are stored. */
class CarPlayAudioState {
    private val audioSetups = AtomicInteger()
    private val mediaSetups = AtomicInteger()
    private val mediaPackets = AtomicLong()
    private val mediaBytes = AtomicLong()
    @Volatile private var owner = "unknown"

    fun accepted(type: Int, audioType: String) {
        audioSetups.incrementAndGet()
        if (type == 102 || audioType.equals("media", true)) mediaSetups.incrementAndGet()
    }
    fun packet(type: Int, audioType: String, bytes: Int) {
        if (bytes > 0 && (type == 102 || audioType.equals("media", true))) {
            mediaPackets.incrementAndGet(); mediaBytes.addAndGet(bytes.toLong())
        }
    }
    fun modes(params: Map<String, Any?>) {
        val resource = (params["resources"] as? List<*>)?.filterIsInstance<Map<*, *>>()
            ?.firstOrNull { (it["resourceID"] as? Number)?.toInt() == 2 } ?: return
        fun value(key: String) = (resource[key] as? Number)?.toInt()?.takeIf { it in 0..3 }?.toString() ?: "other"
        owner = "owner=${value("owner")} borrower=${value("borrower")}" // Raw protocol entities, not guessed route labels.
    }
    fun report(offered: Boolean) = "offered=$offered audioSetups=${audioSetups.get()} mediaSetups=${mediaSetups.get()} mediaPackets=${mediaPackets.get()} mediaBytes=${mediaBytes.get()} audioResource=[$owner]"
}
