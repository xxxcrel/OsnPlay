package com.shilapi.xcertplay

import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.network.ManualHotspotInterface
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/** Validates explicit user selection without guessing an interface or adding stale saved entries. */
internal object ManualHotspotSelection {
    enum class Error(val messageResource: Int) {
        MISSING(R.string.hotspot_network_pick_required),
        UNAVAILABLE(R.string.hotspot_network_pick_unavailable),
    }

    fun choices(available: List<ManualHotspotInterface>, required: Boolean): List<ManualHotspotInterface?> =
        (if (required) emptyList() else listOf(null)) + available.distinctBy { it.name }

    fun error(
        required: Boolean,
        wireless: Boolean,
        mode: WirelessHotspotMode,
        selectedName: String?,
        available: List<ManualHotspotInterface>,
    ): Error? = when {
        !required || !wireless || mode != WirelessHotspotMode.MANUAL -> null
        selectedName.isNullOrBlank() -> Error.MISSING
        available.none { it.name == selectedName } -> Error.UNAVAILABLE
        else -> null
    }
}
