package io.github.ravenor907.muninn.net

/**
 * How hard Muninn works to stay reachable, decided from what the phone is doing. Plain Kotlin (no
 * Android) so the tests can check every case. [Power] feeds it and applies the answer to the
 * relays, Bluetooth, Nearby and Wi-Fi discovery.
 *
 * - Open on screen, in a call, a Kids' TV, plugged in, or Battery saver switched off: fully connected.
 * - Screen off or Muninn in the background: two relays (the healthiest) stay open so messages and
 *   call offers still arrive within seconds, checked together every few minutes so the radio wakes once.
 *   Bluetooth and Nearby go low power and take turns; Wi-Fi discovery slows down (or stops off Wi-Fi).
 * - Android's Battery Saver, or Doze while Android may cut Muninn's network: no live connections;
 *   the 15-minute inbox check picks messages up.
 */
object PowerLogic {
    enum class Radio { FULL, LOW, OFF }

    enum class Why { OPEN, CALL, KIDS_TV, PLUGGED, ALWAYS, BACKGROUND, SCREEN_OFF, BATTERY_SAVER, DOZE }

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
    )

    /**
     * [relays]: how many relay sockets stay open (null: all of them, 0: none). [pingMs]: how often
     * they are checked. [radio]: Bluetooth LE and Nearby. [wifi]: Wi-Fi discovery.
     */
    data class Plan(val why: Why, val relays: Int?, val pingMs: Long, val radio: Radio, val wifi: Radio) {
        val full: Boolean get() = relays == null
        val label: String get() = label(why)
    }

    const val FAST_PING_MS = 30_000L
    /** One check for every socket together; Android may run it a little late to batch it with other apps. */
    const val SLOW_PING_MS = 240_000L
    const val SAVING_RELAYS = 2
    /** Bluetooth and Nearby with the screen off: look for this long, then rest until the period is over. */
    const val DUTY_ON_MS = 15_000L
    const val DUTY_PERIOD_MS = 120_000L
    /** Wait this long before saving more, so a quick glance at the phone doesn't drop and redo connections. */
    const val GRACE_MS = 20_000L
    /** A relay not tried yet counts as this slow to connect. */
    const val UNKNOWN_CONNECT_MS = 5_000L

    fun plan(i: Inputs): Plan {
        val wifiFull = if (i.onWifi) Radio.FULL else Radio.OFF
        fun full(w: Why) = Plan(w, null, FAST_PING_MS, Radio.FULL, wifiFull)
        return when {
            i.inCall -> full(Why.CALL)
            i.kidsTv -> full(Why.KIDS_TV)
            i.foreground -> full(Why.OPEN)
            i.alwaysFull -> full(Why.ALWAYS)
            i.plugged -> full(Why.PLUGGED)
            i.batterySaver -> Plan(Why.BATTERY_SAVER, 0, SLOW_PING_MS, Radio.OFF, Radio.OFF)
            i.doze && !i.unrestricted -> Plan(Why.DOZE, 0, SLOW_PING_MS, Radio.OFF, Radio.OFF)
            !i.screenOn -> Plan(Why.SCREEN_OFF, SAVING_RELAYS, SLOW_PING_MS, Radio.LOW, if (i.onWifi) Radio.LOW else Radio.OFF)
            else -> Plan(Why.BACKGROUND, SAVING_RELAYS, SLOW_PING_MS, Radio.FULL, wifiFull)
        }
    }

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
        Why.BATTERY_SAVER -> "Android's Battery Saver is on: checking for messages about every 15 minutes"
        Why.DOZE -> "Phone is asleep: checking for messages about every 15 minutes"
    }

    private fun rank(p: Plan): Int = when (p.relays) { null -> 3; 0 -> 0; else -> 1 } * 10 + (2 - p.radio.ordinal) * 3 + (2 - p.wifi.ordinal)

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

    /** Bluetooth and Nearby duty cycle: how long to stay in the current half (looking or resting). */
    fun dutyNext(looking: Boolean): Long = if (looking) DUTY_ON_MS else DUTY_PERIOD_MS - DUTY_ON_MS
}
