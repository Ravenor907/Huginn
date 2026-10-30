package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.update.Ed25519
import io.github.ravenor907.muninn.update.Updater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature

/** The update signature check (docs/releases.md) against RFC 8032, the shared vectors and fresh throwaway keys. */
class ReleaseSigTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test
    fun sharedVectors() {
        val cases = JSONObject(javaClass.classLoader!!.getResource("release-sig-vectors.json")!!.readText()).getJSONArray("cases")
        assertTrue(cases.length() >= 10)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals(c.getString("name"), c.getBoolean("valid"),
                Ed25519.verify(hex(c.getString("public")), hex(c.getString("message")), hex(c.getString("signature"))))
        }
    }

    @Test
    fun streamsInPieces() {
        val c = JSONObject(javaClass.classLoader!!.getResource("release-sig-vectors.json")!!.readText()).getJSONArray("cases").getJSONObject(3)
        val msg = hex(c.getString("message"))
        val check = Ed25519.Check(hex(c.getString("public")), hex(c.getString("signature")))
        var off = 0
        while (off < msg.size) { val n = minOf(777, msg.size - off); check.update(msg, off, n); off += n }
        assertTrue(check.valid())
    }

    /** A throwaway key made here (the JVM has Ed25519 even where Android 8 doesn't), as the release job signs. */
    @Test
    fun throwawayKeys() {
        repeat(3) {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val raw = pair.public.encoded.takeLast(32).toByteArray()   // X.509 wrapping, then the 32-byte key
            val file = ByteArray(70_000) { i -> (i * 31 + it).toByte() }
            val sig = Signature.getInstance("Ed25519").run { initSign(pair.private); update(file); sign() }
            assertTrue(Ed25519.verify(raw, file, sig))
            file[12_345] = (file[12_345] + 1).toByte()
            assertFalse(Ed25519.verify(raw, file, sig))
            assertFalse(Updater.signedByProject(file, sig))
        }
    }

    /** CI signs a file with openssl and a fresh key, exactly as the release job does, and leaves it here. */
    @Test
    fun opensslSignature() {
        val path = System.getenv("RELEASE_SIG_CI")
        if (path == null) {
            assertFalse("CI must make the openssl test file", System.getenv("GITHUB_ACTIONS") == "true")
            return
        }
        val dir = File(path)
        val pub = hex(File(dir, "test.pub.hex").readText().trim())
        val file = File(dir, "test.bin").readBytes()
        val sig = File(dir, "test.bin.sig").readBytes()
        assertTrue(Ed25519.verify(pub, file, sig))
        assertFalse(Ed25519.verify(pub, file + byteArrayOf(0), sig))
    }

    @Test
    fun projectKeyMatchesTheDocs() {
        // docs/release-key.pem is the same key, wrapped for openssl (X.509: 12 bytes of header, then the key).
        val pem = File("../../docs/release-key.pem")   // unit tests run in android/app
        assertTrue(pem.exists())
        val der = java.util.Base64.getDecoder().decode(pem.readLines().filterNot { it.startsWith("-----") }.joinToString(""))
        assertEquals(Updater.RELEASE_KEY, der.takeLast(32).joinToString("") { "%02x".format(it) })
    }
}
