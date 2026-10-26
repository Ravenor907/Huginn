package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Folders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contact folders: local only, the three to start with can go, and they're kept. */
class FoldersTest {
    @Test
    fun startAddRenameDeleteAndKeep() {
        var stored: String? = null
        val f = Folders({ stored }, { stored = it })
        assertEquals(Folders.DEFAULTS, f.all.value.map { it.name })
        val family = f.all.value.first().id
        f.put(family, "keyA", true)
        f.put(family, "g:group1", true)
        f.put(family, "keyA", true)   // once only
        assertEquals(listOf("keyA", "g:group1"), f.all.value.first().keys)
        val mine = f.add("  Raids  ")!!
        assertNull(f.add("   "))
        f.rename(mine, "Raid night")
        f.put(mine, "keyA", true)
        assertEquals(listOf("Family", "Raid night"), f.of("keyA").map { it.name })
        f.forget("keyA")
        assertTrue(f.of("keyA").isEmpty())
        // All of them can go, and they don't come back on the next start.
        f.all.value.map { it.id }.forEach { f.delete(it) }
        assertTrue(f.all.value.isEmpty())
        assertTrue(Folders({ stored }, { stored = it }).all.value.isEmpty())
        // Kept across a restart.
        val id = f.add("Kindred")!!
        f.put(id, "keyB", true)
        val again = Folders({ stored }, { stored = it })
        assertEquals(listOf("keyB"), again.all.value.single().keys)
        assertEquals("Kindred", again.all.value.single().name)
    }
}
