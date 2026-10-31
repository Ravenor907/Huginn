package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.tv.TvRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Kids' TV screens' choices that don't need a TV: where the remote starts, and when a permission needs its settings page. */
class TvScreenRulesTest {
    @Test
    fun aPermissionAndroidWontAskForGoesToItsSettingsPage() {
        assertFalse(TvRules.settingsOnly(askedBefore = false, rationale = false))   // never asked: Android's box comes up
        assertFalse(TvRules.settingsOnly(askedBefore = true, rationale = true))     // said no once: Android asks again
        assertTrue(TvRules.settingsOnly(askedBefore = true, rationale = false))     // said no for good: settings only
        assertFalse(TvRules.settingsOnly(askedBefore = false, rationale = true))
    }

    @Test
    fun theRemoteStartsOnTheFirstButtonThere() {
        val quick = listOf("q1", "q2")
        assertEquals("say", TvRules.firstTalkFocus(dictate = true, mic = true, calls = true, quick = quick))
        assertEquals("rec", TvRules.firstTalkFocus(dictate = false, mic = true, calls = true, quick = quick))
        assertEquals("q1", TvRules.firstTalkFocus(dictate = false, mic = false, calls = true, quick = quick))
        // Calls on, no microphone, no quick messages: the Call button is all there is.
        assertEquals("call", TvRules.firstTalkFocus(dictate = false, mic = false, calls = true, quick = emptyList()))
        assertNull(TvRules.firstTalkFocus(dictate = false, mic = false, calls = false, quick = emptyList()))
    }
}
