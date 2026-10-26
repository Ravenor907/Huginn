package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Contacts
import io.github.ravenor907.muninn.net.Vault
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PrivacyTest {
    @Test fun vaultSealsAndOpens() {
        val key = ByteArray(32) { it.toByte() }
        val plain = "secret chats".toByteArray()
        val sealed = Vault.Crypto.seal(key, plain)
        assertEquals(12 + plain.size + 16, sealed.size)
        assertArrayEquals(plain, Vault.Crypto.open(key, sealed))
    }

    @Test fun wrongKeyOrTamperingFails() {
        val key = ByteArray(32) { 1 }
        val sealed = Vault.Crypto.seal(key, "hello".toByteArray())
        assertNull(Vault.Crypto.open(ByteArray(32) { 2 }, sealed))
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 1).toByte()
        assertNull(Vault.Crypto.open(key, sealed))
    }

    @Test fun passwordKeyIsStable() {
        val salt = ByteArray(16) { 7 }
        val a = Vault.Crypto.derive("correct horse", salt, 1000)
        assertEquals(32, a.size)
        assertArrayEquals(a, Vault.Crypto.derive("correct horse", salt, 1000))
        assert(!a.contentEquals(Vault.Crypto.derive("wrong horse", salt, 1000)))
    }

    @Test fun contactCodeRoundTrip() {
        val key = ChatCrypto.b64(ChatCrypto.newKeyPair().public.encoded)
        val code = Contacts.code("Raven 1234", key, "ab".repeat(32))
        val c = Contacts.parse("here's mine: $code")
        assertNotNull(c)
        assertEquals("Raven 1234", c!!.name)
        assertEquals(key, c.chatKey)
        assertEquals("ab".repeat(32), c.mailbox)
        assertNull(Contacts.parse("MUNINN1:garbage"))
    }
}
