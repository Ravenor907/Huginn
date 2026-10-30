package io.github.ravenor907.muninn.net

/**
 * How hard Muninn works to stay reachable, decided from what the phone is doing. Plain Kotlin (no
 * Android) so the tests can check every case. [Power] feeds it and applies the answer to the
 * relays, Bluetooth, Nearby and Wi-Fi discovery.
 *
 * - Open on screen, in a call, a Kids' TV, plugged in, or Battery saver switched off: fully connected.
 * - Screen off or Muninn in the background: two relays (the healthiest) stay open so messages and
 *   call offers still arrive within seconds, checked together every two minutes so the radio wakes once.
 *   Bluetooth and Nearby go low power and take turns; Wi-Fi discovery slows down (or stops off Wi-Fi).
 * - Android's Battery Saver: like the screen being off (two relays, so calls still ring), with Wi-Fi
 *   discovery off.
 * - Doze while Android may cut Muninn's network: no live connections; the 15-minute inbox check picks
 *   messages up.
 * - Settings › Check for messages "Every 15 minutes" or "Every hour": nothing live while Muninn is closed
 *   (unless charging); the inbox check ([checkEveryMinutes]) picks messages up and old calls show as missed.
 *   "Only when open": nothing at all until Muninn is opened, charging or not.
 * - Low battery (on unless switched off, never while charging): below 30% at most every 15 minutes, below
 *   15% at most every hour. Quiet hours: the mode picked for them, while they last. Whichever saves most wins.
 * - Bluetooth and Nearby look for phones close by only while Muninn is open, unless "Find phones close by in
 *   the background" is on. Wi-Fi discovery runs fully only on the Files tab, in a transfer or Direct connect.
 * - Do Not Disturb (or Bedtime mode) counts as quiet hours, unless switched off: Muninn uses the quiet hours mode.
 * - Check less when it's quiet (on unless switched off, never while charging): after [DROWSY_MS] with nothing arriving
 *   and Muninn not opened, the relays kept open in the background are checked every 5 minutes instead of 2 (calls
 *   still ring), and "Every 15 minutes" becomes every 30, then every hour after twice as long.
 */
object PowerLogic {
    enum class Radio { FULL, LOW, OFF }

    enum class Why { OPEN, CALL, KIDS_TV, PLUGGED, ALWAYS, BACKGROUND, SCREEN_OFF, BATTERY_SAVER, DOZE, EVERY_15, EVERY_HOUR, ONLY_OPEN }

    /** Settings › Notifications › Check for messages. */
    enum class Check(val key: String, val title: String) {
        LIVE("live", "Right away"), QUARTER("15m", "Every 15 minutes"), HOUR("hour", "Every hour"), OPEN("open", "Only when open");
        companion object { fun of(key: String?): Check? = entries.firstOrNull { it.key == key } }
    }

    /** How often the background inbox check runs for [c] (0: never, Muninn waits to be opened). */
    fun checkEveryMinutes(c: Check): Long = when (c) { Check.LIVE, Check.QUARTER -> 15; Check.HOUR -> 60; Check.OPEN -> 0 }

    data class Inputs(
        val foreground: Boolean = false,
        val screenOn: Boolean = false,
        val inCall: Boolean = false,
        val kidsTv: Boolean = false,
        val plugged: Boolean = false,
        /** The person switched Muninn's own "Battery saver" off. */
        val alwaysFull: Boolean = false,
        /** Android's Battery Saver. */
        val batterySaver: Boolean = false,
        val doze: Boolean = false,
        /** Muninn may run unrestricted in the background (Doze leaves its network alone then). */
        val unrestricted: Boolean = false,
        val onWifi: Boolean = false,
        /** Settings › Check for messages. */
        val check: Check = Check.LIVE,
        /** Battery level, 0 to 100. */
        val battery: Int = 100,
        /** Settings › Save more when the battery is low. */
        val lowBatterySave: Boolean = true,
        /** In quiet hours right now: the mode they use (null outside them). */
        val quiet: Check? = null,
        /** Settings › Find phones close by in the background. */
        val closeBy: Boolean = false,
        /** The Files tab is open, a transfer is running, or Direct connect is on: Wi-Fi discovery at full. */
        val sharing: Boolean = false,
        /** How long nothing has arrived and Muninn hasn't been opened (ms). */
        val idleMs: Long = 0,
        /** Settings › Check less when it's quiet. */
        val adaptive: Boolean = true,
    )

    /** Check less when it's quiet: how drowsy Muninn is now (0 awake, 1 after [DROWSY_MS], 2 after twice that). */
    fun drowsy(i: Inputs): Int = when {
        !i.adaptive || i.plugged || i.kidsTv -> 0
        i.idleMs >= 2 * DROWSY_MS -> 2
        i.idleMs >= DROWSY_MS -> 1
        else -> 0
    }

    /** How often the background inbox check runs now: [checkEveryMinutes], stretched when it's been quiet a while. */
    fun everyMinutes(i: Inputs): Long {
        val c = effective(i)
        val base = checkEveryMinutes(c)
        if (c != Check.QUARTER) return base
        return when (drowsy(i)) { 2 -> 60; 1 -> 30; else -> base }
    }

    /** What saves battery right now: the person's choice, quiet hours or a low battery, whichever checks least. */
    fun effective(i: Inputs): Check {
        val low = when {
            !i.lowBatterySave || i.plugged -> null
            i.battery < LOW_HOUR -> Check.HOUR
            i.battery < LOW_15 -> Check.QUARTER
            else -> null
        }
        return listOfNotNull(i.check, i.quiet, low).maxBy { it.ordinal }
    }

    /** Quiet hours from [from] to [to] (hours of the day; across midnight when [to] is earlier) include [hour]. */
    fun inQuiet(from: Int, to: Int, hour: Int): Boolean = when {
        from == to -> false
        from < to -> hour in from until to
        else -> hour >= from || hour < to
    }

    /** Why [effective] isn't the person's own choice: "battery" or "quiet", else "". */
    fun because(i: Inputs): String = when (effective(i)) {
        i.check -> ""
        i.quiet -> "quiet"
        else -> "battery"
    }

    /**
     * [relays]: how many relay sockets stay open (null: all of them, 0: none). [pingMs]: how often
     * they are checked. [radio]: Bluetooth LE and Nearby. [wifi]: Wi-Fi discovery.
     */
    data class Plan(val why: Why, val relays: Int?, val pingMs: Long, val radio: Radio, val wifi: Radio, val because: String = "") {
        val full: Boolean get() = relays == null
        val label: String get() = label(why) + when (because) {
            "battery" -> " (the battery is low)"; "quiet" -> " (quiet hours)"
            "idle30" -> ". It's been quiet, so every 30 minutes for now"; "idle60" -> ". It's been quiet, so every hour for now"
            else -> ""
        }
    }

    const val FAST_PING_MS = 30_000L
    /** One check for every socket together; Android may run it a little late to batch it with other apps. */
    const val SLOW_PING_MS = 120_000L
    /** Quiet for a long while: the sockets kept open in the background are checked this often. */
    const val DROWSY_PING_MS = 300_000L
    /** Nothing arrived and Muninn not opened for this long: check less (see [drowsy]). */
    const val DROWSY_MS = 3 * 3_600_000L
    /** A socket that hasn't answered a check within this long is dead and is replaced at once. */
    const val ANSWER_MS = 10_000L
    const val SAVING_RELAYS = 2
    /** Open on screen: the healthiest three of the relays. Senders post to every relay, so nothing is missed. */
    const val OPEN_RELAYS = 3
    /** Low battery: below these, at most every 15 minutes, then at most every hour. */
    const val LOW_15 = 30
    const val LOW_HOUR = 15
    /** Bluetooth and Nearby with the screen off: look for this long, then rest until the period is over. */
    const val DUTY_ON_MS = 15_000L
    const val DUTY_PERIOD_MS = 120_000L
    /** Wait this long before saving more, so a quick glance at the phone doesn't drop and redo connections. */
    const val GRACE_MS = 20_000L
    /** A relay not tried yet counts as this slow to connect. */
    const val UNKNOWN_CONNECT_MS = 5_000L

    fun plan(i: Inputs): Plan {
        val wifiFull = if (i.onWifi) Radio.FULL else Radio.OFF
        // Wi-Fi discovery: fully while sharing files; while open otherwise slowly (Nearby chat and contacts on the
        // Wi-Fi still show up); in the background only for a transfer or Direct connect.
        val wifiOpen = if (!i.onWifi) Radio.OFF else if (i.sharing) Radio.FULL else Radio.LOW
        val wifiAway = if (i.onWifi && i.sharing) Radio.FULL else Radio.OFF
        fun full(w: Why) = Plan(w, null, FAST_PING_MS, Radio.FULL, wifiFull)
        val check = effective(i)
        val why = because(i)
        return when {
            i.inCall -> full(Why.CALL)
            i.kidsTv -> full(Why.KIDS_TV)
            i.foreground -> Plan(Why.OPEN, OPEN_RELAYS, FAST_PING_MS, Radio.FULL, wifiOpen)
            // Nothing in the background, not even on the charger: Muninn catches up when it's opened.
            check == Check.OPEN -> Plan(Why.ONLY_OPEN, 0, SLOW_PING_MS, Radio.OFF, Radio.OFF, why)
            i.alwaysFull && check == Check.LIVE -> full(Why.ALWAYS)
            i.plugged -> full(Why.PLUGGED)
            // No live connections between the scheduled inbox checks; calls that come in between show as missed.
            check == Check.QUARTER -> Plan(Why.EVERY_15, 0, SLOW_PING_MS, Radio.OFF, wifiAway,
                why.ifEmpty { when (drowsy(i)) { 2 -> "idle60"; 1 -> "idle30"; else -> "" } })
            check == Check.HOUR -> Plan(Why.EVERY_HOUR, 0, SLOW_PING_MS, Radio.OFF, wifiAway, why)
            i.doze && !i.unrestricted -> Plan(Why.DOZE, 0, SLOW_PING_MS, Radio.OFF, Radio.OFF)
            // Calls must still ring: the same two relays as with the screen off (checked less once it's been quiet a while).
            i.batterySaver -> Plan(Why.BATTERY_SAVER, SAVING_RELAYS, awayPing(i), if (i.closeBy) Radio.LOW else Radio.OFF, wifiAway)
            !i.screenOn -> Plan(Why.SCREEN_OFF, SAVING_RELAYS, awayPing(i), if (i.closeBy) Radio.LOW else Radio.OFF, wifiAway)
            else -> Plan(Why.BACKGROUND, SAVING_RELAYS, awayPing(i), if (i.closeBy) Radio.FULL else Radio.OFF, wifiAway)
        }
    }

    private fun awayPing(i: Inputs) = if (drowsy(i) > 0) DROWSY_PING_MS else SLOW_PING_MS

    /** What happens once the screen goes off: the line Settings shows (Settings itself is always "open"). */
    fun whenAway(i: Inputs): Plan = plan(i.copy(foreground = false, screenOn = false, inCall = false))

    fun label(w: Why): String = when (w) {
        Why.OPEN -> "Fully connected while Muninn is open"
        Why.CALL -> "In a call: fully connected"
        Why.KIDS_TV -> "Kids' TV: always fully connected so calls ring at once"
        Why.PLUGGED -> "Charging: fully connected"
        Why.ALWAYS -> "Always fully connected"
        Why.BACKGROUND -> "Saving battery while Muninn is in the background"
        Why.SCREEN_OFF -> "Saving battery while the screen is off"
        Why.BATTERY_SAVER -> "Android's Battery Saver is on: saving battery, messages and calls still arrive"
        Why.DOZE -> "Phone is asleep: checking for messages about every 15 minutes"
        Why.EVERY_15 -> "Checking for messages about every 15 minutes. Calls in between show as missed"
        Why.EVERY_HOUR -> "Checking for messages about once an hour. Calls in between show as missed"
        Why.ONLY_OPEN -> "Not checking until you open Muninn. No notifications or calls until then"
    }

    private fun rank(p: Plan): Int = (p.relays?.coerceAtMost(OPEN_RELAYS) ?: (OPEN_RELAYS + 1)) * 10 + (2 - p.radio.ordinal) * 3 + (2 - p.wifi.ordinal)

    /** True when [to] keeps less going than [from]: applied after [GRACE_MS]; anything more is applied at once. */
    fun saves(from: Plan, to: Plan): Boolean = rank(to) < rank(from)

    /** What Muninn knows about a relay: failed tries in a row, how long it took to connect, open right now. */
    data class Health(val failures: Int = 0, val connectMs: Long? = null, val live: Boolean = false)

    /**
     * The [n] relays to keep open (null: all), in the order they are listed. Fewest failures first,
     * then ones already open (no churn), then the quickest to connect, then list order.
     */
    fun pick(relays: List<String>, health: Map<String, Health>, n: Int?): List<String> {
        if (n == null || n >= relays.size) return relays
        if (n <= 0) return emptyList()
        val chosen = relays.withIndex().sortedWith(compareBy(
            { health[it.value]?.failures ?: 0 },
            { if (health[it.value]?.live == true) 0 else 1 },
            { health[it.value]?.connectMs ?: UNKNOWN_CONNECT_MS },
            { it.index },
        )).take(n).map { it.value }.toSet()
        return relays.filter { it in chosen }
    }

    /** A socket asked at [probedAt] that hasn't said anything since is gone (0: never asked). */
    fun silent(probedAt: Long, heardAt: Long): Boolean = probedAt > 0 && heardAt < probedAt

    /**
     * After one check: a socket opened before it ([openedAt]) that hasn't answered within [ANSWER_MS]
     * is replaced right away (a single missed check, not two).
     */
    fun dead(probedAt: Long, heardAt: Long, openedAt: Long, now: Long): Boolean =
        silent(probedAt, heardAt) && openedAt < probedAt && now - probedAt >= ANSWER_MS

    /** Bluetooth and Nearby duty cycle: how long to stay in the current half (looking or resting). */
    fun dutyNext(looking: Boolean): Long = if (looking) DUTY_ON_MS else DUTY_PERIOD_MS - DUTY_ON_MS
}
