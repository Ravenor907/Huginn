package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Nostr
import io.github.ravenor907.muninn.net.Schnorr
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checked against BIP-340's official values and events made by the BIP's reference code. */
class NostrTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("nostr-vectors.json")!!.readText())

    @Test
    fun officialBip340Vector() {
        val o = v.getJSONObject("official")
        val sk = Schnorr.unhex(o.getString("seckey"))
        assertEquals(o.getString("pubkey"), Schnorr.hex(Schnorr.publicKey(sk)))
        val sig = Schnorr.sign(Schnorr.unhex(o.getString("msg")), sk, Schnorr.unhex(o.getString("aux")))
        assertEquals(o.getString("sig"), Schnorr.hex(sig))
        assertTrue(Schnorr.verify(Schnorr.unhex(o.getString("msg")), Schnorr.unhex(o.getString("pubkey")), sig))
    }

    @Test
    fun signsNostrEventsExactlyLikeTheReference() {
        val tags = JSONArray().put(JSONArray().put("p").put(v.getString("recipient")))
        assertEquals(v.getString("serialized"), Nostr.serialize(v.getString("pubkey"), v.getLong("created_at"), v.getInt("kind"), tags, v.getString("content")))
        val ev = Nostr.event(v.getInt("kind"), tags, v.getString("content"), Schnorr.unhex(v.getString("seckey")), v.getLong("created_at"), Schnorr.unhex(v.getString("aux")))
        assertEquals(v.getString("pubkey"), ev.getString("pubkey"))
        assertEquals(v.getString("id"), ev.getString("id"))
        assertEquals(v.getString("sig"), ev.getString("sig"))
        assertEquals(v.getString("id"), Nostr.idOf(ev))
    }

    @Test
    fun rejectsForgedSignatures() {
        val sk = Schnorr.newSecret()
        val msg = ByteArray(32) { 1 }
        val sig = Schnorr.sign(msg, sk)
        assertTrue(Schnorr.verify(msg, Schnorr.publicKey(sk), sig))
        sig[5] = (sig[5].toInt() xor 1).toByte()
        assertFalse(Schnorr.verify(msg, Schnorr.publicKey(sk), sig))
    }
}
