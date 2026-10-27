package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.PowerLogic
import io.github.ravenor907.muninn.net.PowerLogic.Health
import io.github.ravenor907.muninn.net.PowerLogic.Inputs
import io.github.ravenor907.muninn.net.PowerLogic.Radio
import io.github.ravenor907.muninn.net.PowerLogic.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerLogicTest {
    private val phoneAway = Inputs(foreground = false, screenOn = false, onWifi = true)

    @Test
    fun openOnScreenIsFullyConnected() {
        val p = PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true))
        assertEquals(Why.OPEN, p.why)
        assertNull(p.relays)
        assertEquals(PowerLogic.FAST_PING_MS, p.pingMs)
        assertEquals(Radio.FULL, p.radio)
        assertEquals(Radio.FULL, p.wifi)
    }

    @Test
    fun screenOffKeepsTwoRelaysWithOneSlowCheck() {
        val p = PowerLogic.plan(phoneAway)
        assertEquals(Why.SCREEN_OFF, p.why)
        assertEquals(2, p.relays)
        assertTrue(p.pingMs in 240_000L..300_000L)
        assertEquals(Radio.LOW, p.radio)
        assertEquals(Radio.LOW, p.wifi)
        assertEquals("Saving battery while the screen is off", p.label)
    }

    @Test
    fun backgroundWithScreenOnKeepsRadiosFull() {
        val p = PowerLogic.plan(phoneAway.copy(screenOn = true))
        assertEquals(Why.BACKGROUND, p.why)
        assertEquals(2, p.relays)
        assertEquals(Radio.FULL, p.radio)
    }

    @Test
    fun noWifiDiscoveryOffWifi() {
        assertEquals(Radio.OFF, PowerLogic.plan(phoneAway.copy(onWifi = false)).wifi)
        assertEquals(Radio.OFF, PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = false)).wifi)
    }

    @Test
    fun batterySaverDropsLiveConnections() {
        val p = PowerLogic.plan(phoneAway.copy(batterySaver = true))
        assertEquals(Why.BATTERY_SAVER, p.why)
        assertEquals(0, p.relays)
        assertEquals(Radio.OFF, p.radio)
        assertEquals(Radio.OFF, p.wifi)
        // Even when Muninn may run unrestricted: the person asked the phone to save.
        assertEquals(0, PowerLogic.plan(phoneAway.copy(batterySaver = true, unrestricted = true)).relays)
        // Open on screen it still works normally.
        assertNull(PowerLogic.plan(Inputs(foreground = true, screenOn = true, batterySaver = true)).relays)
    }

    @Test
    fun dozeDropsConnectionsOnlyWhenAndroidWouldCutThem() {
        assertEquals(Why.DOZE, PowerLogic.plan(phoneAway.copy(doze = true)).why)
        assertEquals(0, PowerLogic.plan(phoneAway.copy(doze = true)).relays)
        val allowed = PowerLogic.plan(phoneAway.copy(doze = true, unrestricted = true))
        assertEquals(Why.SCREEN_OFF, allowed.why)
        assertEquals(2, allowed.relays)
    }

    @Test
    fun callsKidsTvChargingAndSwitchOffStayFull() {
        val worst = phoneAway.copy(batterySaver = true, doze = true)
        assertEquals(Why.CALL, PowerLogic.plan(worst.copy(inCall = true)).why)
        assertEquals(Why.KIDS_TV, PowerLogic.plan(worst.copy(kidsTv = true)).why)
        assertEquals(Why.ALWAYS, PowerLogic.plan(worst.copy(alwaysFull = true)).why)
        assertEquals(Why.PLUGGED, PowerLogic.plan(phoneAway.copy(plugged = true)).why)
        for (i in listOf(worst.copy(inCall = true), worst.copy(kidsTv = true), worst.copy(alwaysFull = true), phoneAway.copy(plugged = true))) {
            val p = PowerLogic.plan(i)
            assertTrue(p.full)
            assertEquals(Radio.FULL, p.radio)
            assertEquals(PowerLogic.FAST_PING_MS, p.pingMs)
        }
    }

    @Test
    fun callsStillRingInNormalBackground() {
        // Without Battery Saver, at least one relay socket is always live in the background.
        for (screen in listOf(true, false)) for (wifi in listOf(true, false)) for (doze in listOf(false, true)) {
            val p = PowerLogic.plan(Inputs(screenOn = screen, onWifi = wifi, doze = doze && !screen, unrestricted = true))
            assertTrue(p.relays == null || p.relays!! >= 1)
        }
    }

    @Test
    fun settingsLineDescribesTheScreenOff() {
        val open = Inputs(foreground = true, screenOn = true)
        assertEquals("Saving battery while the screen is off", PowerLogic.whenAway(open).label)
        assertEquals(Why.ALWAYS, PowerLogic.whenAway(open.copy(alwaysFull = true)).why)
        assertEquals(Why.BATTERY_SAVER, PowerLogic.whenAway(open.copy(batterySaver = true)).why)
        assertEquals(Why.KIDS_TV, PowerLogic.whenAway(open.copy(kidsTv = true)).why)
        // A call ending is what matters for the line, not the call itself.
        assertEquals(Why.SCREEN_OFF, PowerLogic.whenAway(open.copy(inCall = true)).why)
    }

    @Test
    fun savingWaitsButWakingUpIsImmediate() {
        val full = PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true))
        val off = PowerLogic.plan(phoneAway)
        val saver = PowerLogic.plan(phoneAway.copy(batterySaver = true))
        val bg = PowerLogic.plan(phoneAway.copy(screenOn = true))
        assertTrue(PowerLogic.saves(full, off))
        assertTrue(PowerLogic.saves(off, saver))
        assertTrue(PowerLogic.saves(bg, off))
        assertTrue(PowerLogic.saves(full, bg))
        assertFalse(PowerLogic.saves(off, full))
        assertFalse(PowerLogic.saves(saver, off))
        assertFalse(PowerLogic.saves(off, bg))
        assertFalse(PowerLogic.saves(off, off))
    }

    private val relays = listOf("wss://a", "wss://b", "wss://c", "wss://d", "wss://e")

    @Test
    fun picksAllOrNone() {
        assertEquals(relays, PowerLogic.pick(relays, emptyMap(), null))
        assertEquals(relays, PowerLogic.pick(relays, emptyMap(), 9))
        assertEquals(emptyList<String>(), PowerLogic.pick(relays, emptyMap(), 0))
    }

    @Test
    fun picksTheHealthiestTwo() {
        // Nothing known yet: the first two in the list.
        assertEquals(listOf("wss://a", "wss://b"), PowerLogic.pick(relays, emptyMap(), 2))
        val h = mapOf(
            "wss://a" to Health(failures = 3),
            "wss://b" to Health(connectMs = 900, live = true),
            "wss://c" to Health(connectMs = 200, live = true),
            "wss://d" to Health(connectMs = 100),
            "wss://e" to Health(failures = 1, connectMs = 50, live = true),
        )
        // Fewest failures, then already open, then quickest; kept in list order.
        assertEquals(listOf("wss://b", "wss://c"), PowerLogic.pick(relays, h, 2))
        val h2 = h + ("wss://b" to Health(failures = 2, live = true))
        assertEquals(listOf("wss://c", "wss://d"), PowerLogic.pick(relays, h2, 2))
    }

    @Test
    fun silentSocketsAreFound() {
        assertFalse(PowerLogic.silent(0, 0))           // never asked
        assertTrue(PowerLogic.silent(1_000, 500))      // asked, no answer since
        assertFalse(PowerLogic.silent(1_000, 1_200))   // answered
    }

    @Test
    fun dutyCycleAddsUpToThePeriod() {
        assertEquals(PowerLogic.DUTY_ON_MS, PowerLogic.dutyNext(true))
        assertEquals(PowerLogic.DUTY_PERIOD_MS, PowerLogic.dutyNext(true) + PowerLogic.dutyNext(false))
        assertTrue(PowerLogic.DUTY_ON_MS * 4 <= PowerLogic.DUTY_PERIOD_MS)
    }
}
