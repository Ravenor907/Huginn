package io.github.ravenor907.muninn.forge

import android.content.Context
import io.github.ravenor907.muninn.forge.core.Catalog
import io.github.ravenor907.muninn.forge.core.Distro

/**
 * Experimental systems, kept apart from the main catalog: they live in the app's own
 * labs.json (not the shared distros.json), only show after tapping the version number
 * 10 times, and a problem with them never stops the regular systems from loading.
 */
object Labs {
    private const val ASSET = "labs.json"
    private const val PREFS = "forge"
    private const val PREF_ON = "labs"
    const val TAPS = 10

    /** Every labs system, marked hidden. Empty if labs.json is missing or can't be read. */
    fun catalog(context: Context, taken: Set<String>): List<Distro> = try {
        parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }, taken)
    } catch (e: Exception) {
        emptyList()
    }

    /** Never lets a labs entry replace a regular system with the same id. */
    fun parse(text: String, taken: Set<String>): List<Distro> =
        Catalog.parse(text).filter { it.id !in taken }.map { it.copy(hidden = true) }

    fun isOn(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_ON, on).apply()
    }

    private const val PREF_STREAM = "labs.stream"
    private const val PREF_SKIP_CHECK = "labs.skipCheck"

    private fun pref(context: Context, key: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(key, false)

    private fun setPref(context: Context, key: String, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply()

    /** Labs setting: write the image while it downloads. Only counts while Labs is on. */
    fun streamWrite(context: Context) = isOn(context) && pref(context, PREF_STREAM)
    fun streamWriteSetting(context: Context) = pref(context, PREF_STREAM)
    fun setStreamWrite(context: Context, on: Boolean) = setPref(context, PREF_STREAM, on)

    /** Labs setting: don't read the drive back after writing. Only counts while Labs is on. */
    fun skipCheck(context: Context) = isOn(context) && pref(context, PREF_SKIP_CHECK)
    fun skipCheckSetting(context: Context) = pref(context, PREF_SKIP_CHECK)
    fun setSkipCheck(context: Context, on: Boolean) = setPref(context, PREF_SKIP_CHECK, on)
}
