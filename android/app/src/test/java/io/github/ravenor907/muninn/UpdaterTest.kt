package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.update.Updater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun readsReleaseTags() {
        assertEquals("0.3", Updater.versionOf("v0.3"))
        assertEquals("0.31", Updater.versionOf("v0.31"))
        assertEquals("1.0", Updater.versionOf("v1.0"))
        assertNull(Updater.versionOf("v0.3-test1"))         // old test builds are skipped
        assertNull(Updater.versionOf("muninn-v0.1-test1"))
        assertNull(Updater.versionOf("v0.3.1"))
        assertNull(Updater.versionOf("nightly"))
    }

    @Test
    fun versionsReadAsDecimals() {
        assertTrue(Updater.rank("0.31") > Updater.rank("0.3"))
        assertTrue(Updater.rank("0.32") > Updater.rank("0.31"))
        assertTrue(Updater.rank("0.4") > Updater.rank("0.39"))
        assertTrue(Updater.rank("1.0") > Updater.rank("0.99"))
        assertEquals(Updater.rank("0.3"), Updater.rank("0.30"))
    }

    @Test
    fun oldTestBuildsUpdateToTheRelease() {
        assertTrue(Updater.rank("0.3") > Updater.rank("0.3 Test 1"))
        assertTrue(Updater.rank("0.3 Test 1") > Updater.rank("0.2"))
    }

    @Test
    fun readsTagFromLatestLink() {
        assertEquals("v0.6", Updater.tagFromLatest("https://github.com/Ravenor907/Huginn-Muninn/releases/tag/v0.6"))
        assertEquals("v0.61", Updater.tagFromLatest("https://github.com/Ravenor907/Huginn-Muninn/releases/tag/v0.61?x=1"))
        assertNull(Updater.tagFromLatest("https://github.com/Ravenor907/Huginn-Muninn/releases"))
    }
}
