package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun readsMuninnTagsOnly() {
        assertEquals("0.1 Test 2", Updater.versionOf("muninn-v0.1-test2"))
        assertEquals("1.0", Updater.versionOf("muninn-v1.0"))
        assertNull(Updater.versionOf("v0.1-test2"))   // a Huginn release
    }

    @Test
    fun stableBeatsItsTests() {
        assertTrue(Updater.rank("0.2") > Updater.rank("0.2 Test 9"))
        assertTrue(Updater.rank("0.2 Test 1") > Updater.rank("0.1"))
        assertTrue(Updater.rank("0.1 Test 10") > Updater.rank("0.1 Test 9"))
    }
}
