package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.PowerLogic
import io.github.ravenor907.muninn.net.PowerLogic.Check
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
    fun openOnScreenIsConnectedThroughTheHealthiestThree() {
        val p = PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true))
        assertEquals(Why.OPEN, p.why)
        assertEquals(PowerLogic.OPEN_RELAYS, p.relays)
        assertEquals(PowerLogic.FAST_PING_MS, p.pingMs)
        assertEquals(Radio.FULL, p.radio)
        // Wi-Fi discovery is slow unless files are being shared.
        assertEquals(Radio.LOW, p.wifi)
        assertEquals(Radio.FULL, PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true, sharing = true)).wifi)
    }

    @Test
    fun screenOffKeepsTwoRelaysWithOneSlowCheck() {
        val p = PowerLogic.plan(phoneAway)
        assertEquals(Why.SCREEN_OFF, p.why)
        assertEquals(2, p.relays)
        // Every two minutes, so a dead socket is found (and calls ring again) within about two minutes.
        assertEquals(120_000L, p.pingMs)
        // Close-by scanning and Wi-Fi discovery rest unless switched on or sharing files.
        assertEquals(Radio.OFF, p.radio)
        assertEquals(Radio.OFF, p.wifi)
        assertEquals(Radio.LOW, PowerLogic.plan(phoneAway.copy(closeBy = true)).radio)
        assertEquals(Radio.FULL, PowerLogic.plan(phoneAway.copy(sharing = true)).wifi)
        assertEquals("Saving battery while the screen is off", p.label)
    }

    @Test
    fun backgroundWithScreenOnScansOnlyWhenAsked() {
        val p = PowerLogic.plan(phoneAway.copy(screenOn = true))
        assertEquals(Why.BACKGROUND, p.why)
        assertEquals(2, p.relays)
        assertEquals(Radio.OFF, p.radio)
        assertEquals(Radio.FULL, PowerLogic.plan(phoneAway.copy(screenOn = true, closeBy = true)).radio)
    }

    @Test
    fun noWifiDiscoveryOffWifi() {
        assertEquals(Radio.OFF, PowerLogic.plan(phoneAway.copy(onWifi = false)).wifi)
        assertEquals(Radio.OFF, PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = false)).wifi)
    }

    @Test
    fun batterySaverSavesLikeTheScreenOffButCallsStillRing() {
        for (screen in listOf(false, true)) for (wifi in listOf(false, true)) {
            // Screen on with Muninn in the background, or screen off: two relays, the slow check, radios low.
            val p = PowerLogic.plan(phoneAway.copy(screenOn = screen, onWifi = wifi, batterySaver = true))
            assertEquals(Why.BATTERY_SAVER, p.why)
            assertEquals(PowerLogic.SAVING_RELAYS, p.relays)
            assertEquals(PowerLogic.SLOW_PING_MS, p.pingMs)
            assertEquals(Radio.OFF, p.radio)
            assertEquals(Radio.OFF, p.wifi)
            assertEquals(Radio.LOW, PowerLogic.plan(phoneAway.copy(screenOn = screen, onWifi = wifi, batterySaver = true, closeBy = true)).radio)
        }
        assertEquals(2, PowerLogic.plan(phoneAway.copy(batterySaver = true, unrestricted = true)).relays)
        // Battery Saver and Doze without the exemption: Android cuts the network, so no live sockets.
        assertEquals(Why.DOZE, PowerLogic.plan(phoneAway.copy(batterySaver = true, doze = true)).why)
        assertEquals(0, PowerLogic.plan(phoneAway.copy(batterySaver = true, doze = true)).relays)
        assertEquals(2, PowerLogic.plan(phoneAway.copy(batterySaver = true, doze = true, unrestricted = true)).relays)
        // Open on screen it still works normally.
        assertEquals(PowerLogic.OPEN_RELAYS, PowerLogic.plan(Inputs(foreground = true, screenOn = true, batterySaver = true)).relays)
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
        // Battery Saver or not, at least one relay socket is always live in the background (unless Doze cuts the network).
        for (screen in listOf(true, false)) for (wifi in listOf(true, false)) for (doze in listOf(false, true)) for (saver in listOf(false, true)) {
            val p = PowerLogic.plan(Inputs(screenOn = screen, onWifi = wifi, doze = doze && !screen, unrestricted = true, batterySaver = saver))
            assertTrue(p.relays == null || p.relays!! >= 1)
        }
        for (saver in listOf(false, true)) assertTrue(PowerLogic.plan(Inputs(screenOn = true, batterySaver = saver)).relays!! >= 1)
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
        val doze = PowerLogic.plan(phoneAway.copy(doze = true))
        val bg = PowerLogic.plan(phoneAway.copy(screenOn = true))
        assertTrue(PowerLogic.saves(full, off))
        assertTrue(PowerLogic.saves(full, bg))
        assertFalse(PowerLogic.saves(off, full))
        assertFalse(PowerLogic.saves(off, off))
        // Nothing scanning and no Wi-Fi discovery: the screen off, Battery Saver and the background cost the same.
        assertFalse(PowerLogic.saves(off, saver)); assertFalse(PowerLogic.saves(saver, off))
        assertFalse(PowerLogic.saves(bg, off)); assertFalse(PowerLogic.saves(off, bg))
        // With close-by scanning on, the screen being on scans more than off.
        val bgScan = PowerLogic.plan(phoneAway.copy(screenOn = true, closeBy = true))
        val offScan = PowerLogic.plan(phoneAway.copy(closeBy = true))
        assertTrue(PowerLogic.saves(bgScan, offScan))
        assertFalse(PowerLogic.saves(offScan, bgScan))
        assertTrue(PowerLogic.saves(saver, doze))
        assertFalse(PowerLogic.saves(doze, saver))
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
    fun oneMissedCheckReplacesASocket() {
        val a = PowerLogic.ANSWER_MS
        assertTrue(a <= 15_000L)
        // Asked at 1000, opened before, no answer: dead once the answer time has passed, not a whole period later.
        assertTrue(PowerLogic.dead(probedAt = 1_000, heardAt = 500, openedAt = 0, now = 1_000 + a))
        assertFalse(PowerLogic.dead(probedAt = 1_000, heardAt = 500, openedAt = 0, now = 1_000 + a - 1))   // still waiting
        assertFalse(PowerLogic.dead(probedAt = 1_000, heardAt = 1_100, openedAt = 0, now = 1_000 + a))    // answered
        assertFalse(PowerLogic.dead(probedAt = 1_000, heardAt = 0, openedAt = 1_500, now = 1_000 + a))    // opened after the check
        assertFalse(PowerLogic.dead(probedAt = 0, heardAt = 0, openedAt = 0, now = a))                    // never asked
        // A dead socket is found within one check period plus the answer time.
        assertTrue(PowerLogic.SLOW_PING_MS + a <= 135_000L)
    }

    @Test
    fun dutyCycleAddsUpToThePeriod() {
        assertEquals(PowerLogic.DUTY_ON_MS, PowerLogic.dutyNext(true))
        assertEquals(PowerLogic.DUTY_PERIOD_MS, PowerLogic.dutyNext(true) + PowerLogic.dutyNext(false))
        assertTrue(PowerLogic.DUTY_ON_MS * 4 <= PowerLogic.DUTY_PERIOD_MS)
    }

    @Test
    fun scheduledChecksKeepNothingLiveWhileClosed() {
        for ((c, why) in listOf(Check.QUARTER to Why.EVERY_15, Check.HOUR to Why.EVERY_HOUR)) {
            val p = PowerLogic.plan(phoneAway.copy(check = c))
            assertEquals(why, p.why)
            assertEquals(0, p.relays)
            assertEquals(Radio.OFF, p.radio)
            assertEquals(Radio.OFF, p.wifi)
            // Open, in a call or charging: fully connected as always.
            assertEquals(Why.OPEN, PowerLogic.plan(phoneAway.copy(check = c, foreground = true, screenOn = true)).why)
            assertEquals(Why.CALL, PowerLogic.plan(phoneAway.copy(check = c, inCall = true)).why)
            assertEquals(Why.PLUGGED, PowerLogic.plan(phoneAway.copy(check = c, plugged = true)).why)
            // Maximum speed belongs to "Right away" only.
            assertEquals(why, PowerLogic.plan(phoneAway.copy(check = c, alwaysFull = true)).why)
        }
        assertEquals(15L, PowerLogic.checkEveryMinutes(Check.QUARTER))
        assertEquals(60L, PowerLogic.checkEveryMinutes(Check.HOUR))
    }

    @Test
    fun onlyWhenOpenIsQuietEvenOnTheCharger() {
        val p = PowerLogic.plan(phoneAway.copy(check = Check.OPEN, plugged = true, alwaysFull = true))
        assertEquals(Why.ONLY_OPEN, p.why)
        assertEquals(0, p.relays)
        assertEquals(0L, PowerLogic.checkEveryMinutes(Check.OPEN))
        // Opened, or a call it started: fully connected.
        assertEquals(Why.OPEN, PowerLogic.plan(phoneAway.copy(check = Check.OPEN, foreground = true, screenOn = true)).why)
        assertEquals(Why.CALL, PowerLogic.plan(phoneAway.copy(check = Check.OPEN, inCall = true)).why)
    }

    @Test
    fun rightAwayIsUnchangedAndKidsTvStaysLive() {
        assertEquals(Why.SCREEN_OFF, PowerLogic.plan(phoneAway.copy(check = Check.LIVE)).why)
        assertEquals(Why.ALWAYS, PowerLogic.plan(phoneAway.copy(check = Check.LIVE, alwaysFull = true)).why)
        for (c in Check.entries) assertEquals(Why.KIDS_TV, PowerLogic.plan(phoneAway.copy(check = c, kidsTv = true)).why)
        assertEquals(Check.HOUR, Check.of("hour"))
        assertNull(Check.of("weekly"))
    }

    @Test
    fun aLowBatterySavesMoreUntilCharged() {
        val away = phoneAway.copy(check = Check.LIVE)
        assertEquals(Check.LIVE, PowerLogic.effective(away.copy(battery = 30)))
        assertEquals(Check.QUARTER, PowerLogic.effective(away.copy(battery = 29)))
        assertEquals(Check.HOUR, PowerLogic.effective(away.copy(battery = 14)))
        // Charging, or switched off: the person's own choice.
        assertEquals(Check.LIVE, PowerLogic.effective(away.copy(battery = 5, plugged = true)))
        assertEquals(Check.LIVE, PowerLogic.effective(away.copy(battery = 5, lowBatterySave = false)))
        // Never less saving than the person chose.
        assertEquals(Check.OPEN, PowerLogic.effective(away.copy(battery = 20, check = Check.OPEN)))
        val p = PowerLogic.plan(away.copy(battery = 20))
        assertEquals(Why.EVERY_15, p.why)
        assertEquals(0, p.relays)
        assertTrue(p.label.endsWith("(the battery is low)"))
        // Open or in a call it still connects fully.
        assertEquals(Why.OPEN, PowerLogic.plan(away.copy(battery = 5, foreground = true, screenOn = true)).why)
        assertEquals(Why.CALL, PowerLogic.plan(away.copy(battery = 5, inCall = true)).why)
    }

    @Test
    fun quietHoursUseTheirModeWhileTheyLast() {
        assertTrue(PowerLogic.inQuiet(23, 7, 23))
        assertTrue(PowerLogic.inQuiet(23, 7, 3))
        assertTrue(!PowerLogic.inQuiet(23, 7, 7))
        assertTrue(!PowerLogic.inQuiet(23, 7, 12))
        assertTrue(PowerLogic.inQuiet(13, 15, 14))
        assertTrue(!PowerLogic.inQuiet(9, 9, 9))
        val p = PowerLogic.plan(phoneAway.copy(quiet = Check.OPEN, plugged = true))
        assertEquals(Why.ONLY_OPEN, p.why)
        assertTrue(p.label.endsWith("(quiet hours)"))
        assertEquals(Why.EVERY_HOUR, PowerLogic.plan(phoneAway.copy(quiet = Check.HOUR)).why)
        // The battery being low too: whichever saves most.
        assertEquals(Check.OPEN, PowerLogic.effective(phoneAway.copy(quiet = Check.OPEN, battery = 10)))
        // A Kids' TV ignores both.
        assertEquals(Why.KIDS_TV, PowerLogic.plan(phoneAway.copy(quiet = Check.OPEN, battery = 5, kidsTv = true)).why)
    }

    @Test
    fun savingMoreStillWaitsAMoment() {
        val open = PowerLogic.plan(Inputs(foreground = true, screenOn = true, onWifi = true))
        val away = PowerLogic.plan(phoneAway)
        assertTrue(PowerLogic.saves(open, away))
        assertTrue(!PowerLogic.saves(away, open))
        // Fully connected (every relay) ranks above open on screen (three).
        assertTrue(PowerLogic.saves(PowerLogic.plan(phoneAway.copy(plugged = true)), open))
    }

    @Test
    fun checksLessWhenItsBeenQuiet() {
        val h = 3_600_000L
        // Right away: still two relays (calls ring), checked every 5 minutes instead of 2 after 3 quiet hours.
        assertEquals(PowerLogic.SLOW_PING_MS, PowerLogic.plan(phoneAway.copy(idleMs = 2 * h)).pingMs)
        val drowsy = PowerLogic.plan(phoneAway.copy(idleMs = 3 * h))
        assertEquals(2, drowsy.relays)
        assertEquals(PowerLogic.DROWSY_PING_MS, drowsy.pingMs)
        // Every 15 minutes: every 30 after 3 hours, every hour after 6.
        val quarter = phoneAway.copy(check = Check.QUARTER)
        assertEquals(15L, PowerLogic.everyMinutes(quarter))
        assertEquals(30L, PowerLogic.everyMinutes(quarter.copy(idleMs = 3 * h)))
        assertEquals(60L, PowerLogic.everyMinutes(quarter.copy(idleMs = 6 * h)))
        assertTrue(PowerLogic.plan(quarter.copy(idleMs = 6 * h)).label.contains("every hour for now"))
        // Every hour and Only when open don't change.
        assertEquals(60L, PowerLogic.everyMinutes(phoneAway.copy(check = Check.HOUR, idleMs = 9 * h)))
        assertEquals(0L, PowerLogic.everyMinutes(phoneAway.copy(check = Check.OPEN, idleMs = 9 * h)))
        // Never while charging, on a Kids' TV, or with the switch off.
        assertEquals(15L, PowerLogic.everyMinutes(quarter.copy(idleMs = 9 * h, adaptive = false)))
        assertEquals(0, PowerLogic.drowsy(phoneAway.copy(idleMs = 9 * h, plugged = true)))
        assertEquals(0, PowerLogic.drowsy(phoneAway.copy(idleMs = 9 * h, kidsTv = true)))
        // A low battery still wins over a stretched check: below 15% is every hour anyway.
        assertEquals(60L, PowerLogic.everyMinutes(quarter.copy(battery = 10)))
    }
}
