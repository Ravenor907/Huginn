package io.github.ravenor907.muninn.forge.core

/** What the phone reports right before a job starts. Null means Android didn't say. */
data class PhoneState(
    val batteryPercent: Int?,
    val charging: Boolean,
    val online: Boolean,
    val unmetered: Boolean,
    /** False while Android's battery optimization may pause the app in the background. */
    val backgroundAllowed: Boolean,
)

enum class Fix { BACKGROUND }

/** One line of the "Before you start" list. [required] lines block the start until [ok]. */
data class Check(val ok: Boolean, val required: Boolean, val title: String, val detail: String, val fix: Fix? = null)

/**
 * Checks run before erasing a drive. A job takes a while (a multi-GB download,
 * then a full write and read-back) and powers the stick from the phone's battery,
 * so it should only start when it's likely to finish.
 */
object Preflight {
    const val MIN_BATTERY = 30

    fun checks(p: PhoneState, download: Boolean = true): List<Check> = listOfNotNull(
        Check(
            ok = p.charging || p.batteryPercent == null || p.batteryPercent >= MIN_BATTERY,
            required = true,
            title = "Battery $MIN_BATTERY% or more, or charging",
            detail = if (p.charging) {
                "Charging"
            } else {
                "At ${p.batteryPercent ?: "?"}%. The phone also powers the USB drive, so " +
                    if ((p.batteryPercent ?: 100) >= MIN_BATTERY) "charging is still a good idea." else "charge it first."
            },
        ),
        if (!download) null else Check(
            ok = p.online,
            required = true,
            title = "Connected to the internet",
            detail = if (p.online) "Needed to download the latest release" else "Connect to Wi-Fi first",
        ),
        if (!download) null else Check(
            ok = p.unmetered,
            required = false,
            title = "On Wi-Fi",
            detail = if (p.unmetered) "Good for a download of several GB" else "You're on mobile data. The download is several GB.",
        ),
        Check(
            ok = p.backgroundAllowed,
            required = false,
            title = "Allowed to run in the background",
            detail = if (p.backgroundAllowed) {
                "Android won't pause the job"
            } else {
                "Android may pause the job if you leave the app. Tap, then set Battery to Unrestricted."
            },
            fix = if (p.backgroundAllowed) null else Fix.BACKGROUND,
        ),
    )

    /** Every required check passes. */
    fun canStart(checks: List<Check>, acknowledged: Boolean = true) = acknowledged && checks.all { it.ok || !it.required }

    /**
     * Turns a failure into advice. Network errors right after the app went to the
     * background are almost always Android pausing it, so say that plainly.
     */
    fun explain(message: String, wasInBackground: Boolean, networkError: Boolean): String = when {
        networkError && wasInBackground ->
            "The download stopped while Handheld Forge was in the background. Keep the app open with the " +
                "screen on until it's done, then try again. It picks up where it left off."
        networkError -> "The connection dropped. Check your Wi-Fi and try again. The download picks up where it left off."
        wasInBackground -> "$message\n\nThis happened while Handheld Forge was in the background. Keep the app open until it's done."
        else -> message
    }
}
