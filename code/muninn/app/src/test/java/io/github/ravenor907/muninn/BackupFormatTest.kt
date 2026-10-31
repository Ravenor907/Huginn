package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.BackupFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BackupFormatTest {
    // Fewer rounds keep the test quick; the format and the math are the same.
    private val rounds = 100_000

    private fun files() = listOf(
        "chats.json" to """{"abc":[{"text":"hello"}]}""".toByteArray(),
        "contacts.json" to "[]".toByteArray(),
        "photos/msg-1" to ByteArray(200_000) { (it * 31).toByte() },
        "empty" to ByteArray(0),
        "voice/ünïcode name" to byteArrayOf(1, 2, 3),
    )

    private fun make(password: String = "correct horse", list: List<Pair<String, ByteArray>> = files(), app: String = "muninn"): ByteArray {
        val out = ByteArrayOutputStream()
        val steps = ArrayList<Float>()
        val n = BackupFormat.write(out, password, list.map { (n, d) -> BackupFormat.Entry(n) { d } }, app = app,
            created = "2026-10-26T12:00:00Z", rounds = rounds) { steps += it }
        assertEquals(list.size, n)
        if (list.isNotEmpty()) assertEquals(1f, steps.last(), 0f)
        return out.toByteArray()
    }

    @Test fun roundTrip() {
        val opened = BackupFormat.open(make(), "correct horse")
        assertEquals(files().map { it.first }, opened.files.map { it.first })
        files().zip(opened.files).forEach { (a, b) -> assertArrayEquals(a.second, b.second) }
        assertEquals(1, opened.header.v)
        assertEquals("muninn", opened.header.app)
        assertEquals("2026-10-26T12:00:00Z", opened.header.created)
        assertEquals(rounds, opened.header.rounds)
        assertEquals(16, opened.header.salt.size)
    }

    @Test fun emptyBackupOpens() {
        assertEquals(0, BackupFormat.open(make(list = emptyList()), "correct horse").files.size)
    }

    @Test fun skippedEntriesAreLeftOut() {
        val out = ByteArrayOutputStream()
        val n = BackupFormat.write(out, "pw-12345678", listOf(BackupFormat.Entry("a") { null }, BackupFormat.Entry("b") { byteArrayOf(9) }), rounds = rounds)
        assertEquals(1, n)
        assertEquals(listOf("b"), BackupFormat.open(out.toByteArray(), "pw-12345678").files.map { it.first })
    }

    /** The exact bytes Huginn expects: magic, BE length, header, IV + ciphertext + tag, container records. */
    @Test fun layoutMatchesTheSpec() {
        val b = make(list = listOf("x/y" to byteArrayOf(7, 8)))
        assertArrayEquals("MUNB1".toByteArray(), b.copyOfRange(0, 5))
        val len = ByteBuffer.wrap(b, 5, 4).int
        val json = String(b, 9, len)
        assertTrue(json, json.startsWith("{\"v\":1,\"salt\":\""))
        assertTrue(json, json.contains("\"rounds\":$rounds") && json.contains("\"app\":\"muninn\"") && json.contains("\"created\":\"2026-10-26T12:00:00Z\""))
        val at = 9 + len
        val (h, start) = BackupFormat.header(b)
        assertEquals(at, start)
        val key = BackupFormat.derive("correct horse", h.salt, h.rounds)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b, at, 12))
        val plain = c.doFinal(b, at + 12, b.size - at - 12)
        assertEquals(b.size - at - 12 - 16, plain.size)   // 12-byte IV in front, 16-byte tag at the end
        val p = ByteBuffer.wrap(plain)
        assertEquals(3, p.int); assertEquals("x/y", String(ByteArray(3).also { p.get(it) }))
        assertEquals(2, p.int); assertArrayEquals(byteArrayOf(7, 8), ByteArray(2).also { p.get(it) })
        assertFalse(p.hasRemaining())
    }

    /** A header written by another program (spaces, other key order) still reads. */
    @Test fun headerWithSpacesReads() {
        val salt = java.util.Base64.getEncoder().encodeToString(ByteArray(16) { 3 })
        val json = """{ "app": "muninn", "created": "2026-01-02T03:04:05Z", "rounds": 310000, "salt": "$salt", "v": 1 }""".toByteArray()
        val b = "MUNB1".toByteArray() + ByteBuffer.allocate(4).putInt(json.size).array() + json + ByteArray(40)
        val (h, at) = BackupFormat.header(b)
        assertEquals(310_000, h.rounds); assertEquals("2026-01-02T03:04:05Z", h.created); assertEquals(9 + json.size, at)
    }

    @Test fun wrongPasswordIsRefused() {
        try { BackupFormat.open(make(), "wrong horse"); fail() } catch (e: BackupFormat.WrongPassword) {}
    }

    @Test fun tamperingIsRefused() {
        val b = make()
        b[b.size - 40] = (b[b.size - 40].toInt() xor 1).toByte()
        try { BackupFormat.open(b, "correct horse"); fail() } catch (e: BackupFormat.WrongPassword) {}
    }

    @Test fun otherFilesAndHuginnBackupsAreRefused() {
        try { BackupFormat.open("hello there, not a backup".toByteArray(), "x"); fail() } catch (e: BackupFormat.NotABackup) {}
        try { BackupFormat.open(make(app = "huginn"), "correct horse"); fail() } catch (e: BackupFormat.NotABackup) {
            assertTrue(e.message!!.contains("Huginn"))
        }
        // A header that asks for too few rounds is not trusted.
        val weak = """{"v":1,"salt":"${java.util.Base64.getEncoder().encodeToString(ByteArray(16))}","rounds":1,"created":"","app":"muninn"}""".toByteArray()
        try { BackupFormat.header("MUNB1".toByteArray() + ByteBuffer.allocate(4).putInt(weak.size).array() + weak); fail() } catch (e: BackupFormat.NotABackup) {}
    }

    @Test fun damagedContainerIsRefused() {
        try { BackupFormat.unpack(ByteBuffer.allocate(8).putInt(100).putInt(0).array()); fail() } catch (e: BackupFormat.NotABackup) {}
        try { BackupFormat.unpack(ByteBuffer.allocate(9).putInt(1).put(65).putInt(50).array()); fail() } catch (e: BackupFormat.NotABackup) {}
    }

    @Test fun namesStayInsideTheFolder() {
        listOf("chats.json", "photos/a", "ratchet/s-1.json").forEach { assertTrue(it, BackupFormat.safeName(it)) }
        listOf("", "/etc/passwd", "../x", "a/../../b", "a//b", "a\\b", "./a", "a/").forEach { assertFalse(it, BackupFormat.safeName(it)) }
    }
}
