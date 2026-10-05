package com.shilapi.xcertplay

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class OsnUiDeviceMonitorTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.addLast(command) }
        fun next() = tasks.removeFirst().run()
    }

    @Test fun repeatedTicksAndForcedRequestsCoalesceInsteadOfQueuingBehindASlowService() {
        val work = Queue(); val main = Queue()
        var now = 0L; var calls = 0; var published = 0
        val monitor = OsnUiDeviceMonitor({ calls++; OsnUiDeviceSnapshot() }, main, { published++ }, work, { now })
        assertTrue(monitor.request())
        repeat(100) { assertFalse(monitor.request(force = true)) }
        assertEquals(1, work.tasks.size)
        work.next(); main.next()
        assertEquals(1, calls); assertEquals(1, published)
        assertFalse(monitor.request())
        now = 10_000
        assertTrue(monitor.request())
    }

    @Test fun closingTheWindowDropsAlreadyQueuedMainThreadResults() {
        val work = Queue(); val main = Queue(); var updates = 0
        val monitor = OsnUiDeviceMonitor({ OsnUiDeviceSnapshot() }, main, { updates++ }, work, clock = { 0L })
        monitor.request(); work.next()
        monitor.close(); main.next()
        assertEquals(0, updates)
        assertFalse(monitor.request(force = true))
    }

    @Test fun anOlderResultCannotOverwriteANewerSnapshot() {
        val work = Queue(); val main = Queue(); val updates = mutableListOf<Boolean?>()
        var enabled = false
        val monitor = OsnUiDeviceMonitor({ OsnUiDeviceSnapshot(bluetoothEnabled = enabled) }, main, { updates += it.bluetoothEnabled }, work, clock = { 0L })
        monitor.request(); work.next()
        enabled = true
        monitor.request(force = true); work.next()
        main.next(); main.next()
        assertEquals(listOf(true), updates)
    }

    @Test fun aFailedServiceReadRetainsPreviousDataAndBacksOffWithoutLeakingErrorContents() {
        val work = Queue(); val main = Queue(); var fail = false
        val monitor = OsnUiDeviceMonitor({ if (fail) throw SecurityException("private vendor error") else OsnUiDeviceSnapshot(hotspotEnabled = true) }, main, {}, work, clock = { 0L })
        monitor.request(); work.next(); main.next()
        fail = true
        monitor.request(force = true); work.next()
        assertEquals(true, monitor.latest?.hotspotEnabled)
        assertTrue(monitor.report().contains("SecurityException"))
        assertFalse(monitor.report().contains("private vendor error"))
        assertFalse(monitor.request())
    }
}
