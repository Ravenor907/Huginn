package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.net.Proto06
import io.github.ravenor907.muninn.net.call.CallLogic
import io.github.ravenor907.muninn.net.call.RoomLogic
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/** The room part of tests/proto06-vectors.json (voice rooms and group calls), which Huginn checks too. */
class RoomTest {
    private val v = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText())
    private val r = v.getJSONObject("room")
    private val keys = v.getJSONObject("keys")
    private val msgs = r.getJSONObject("messages")
    private fun state(name: String) = Proto06.parseState(v.getJSONObject("group").getJSONObject("states").getJSONObject(name).getJSONObject("state"))
    private fun msg(name: String) = msgs.getJSONObject(name).getJSONObject("msg")

    @Test
    fun limitsAndDomain() {
        assertEquals(RoomLogic.DOMAIN, v.getJSONObject("domains").getString("room"))
        assertEquals(RoomLogic.MAX_PEOPLE, r.getInt("maxPeople"))
        assertEquals(RoomLogic.FRESH_PAST_MS, r.getLong("freshPastMs"))
        assertEquals(RoomLogic.FRESH_AHEAD_MS, r.getLong("freshAheadMs"))
        assertEquals(RoomLogic.PRESENCE_TTL_MS, r.getLong("presenceTtlMs"))
        assertTrue(RoomLogic.isId(r.getString("voiceRoom"), 8))
        assertTrue(RoomLogic.isGroupCall(r.getString("groupCall")))
        assertFalse(RoomLogic.isRoomId("main"))
        assertTrue(RoomLogic.isGroupCall(RoomLogic.newGroupCallId()))
        assertFalse(RoomLogic.full(6))
        assertTrue(RoomLogic.full(7))
    }

    @Test
    fun signedBytes() {
        for (k in msgs.keys()) {
            val x = msgs.getJSONObject(k)
            val m = x.getJSONObject("msg")
            val canon = Proto06.canon(m.keys().asSequence().filter { it != "sig" }.toList().associateWith { m.opt(it) })
            assertEquals("signed $k", x.getString("signed"), RoomLogic.DOMAIN + "\n" + canon)
        }
    }

    @Test
    fun accept() {
        val a = r.getJSONArray("accept")
        assertTrue(a.length() > 0)
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            val st = if (c.isNull("state")) null else state(c.getString("state"))
            val got = RoomLogic.accept(st, keys.getString(c.getString("me")), keys.getString(c.getString("from")), msg(c.getString("msg")), c.getLong("now"))
            assertEquals("accept ${c.getString("msg")} at ${c.getString("me")}", c.getString("result"), got)
        }
    }

    @Test
    fun presence() {
        val p = RoomLogic.Presence()
        val g = r.getString("g")
        val ch = r.getString("voiceRoom")
        val steps = r.getJSONArray("presence")
        for (i in 0 until steps.length()) {
            val s = steps.getJSONObject(i)
            val now = s.getLong("now")
            var changed = false
            if (!s.isNull("msg")) changed = p.apply(keys.getString(s.getString("from")), msg(s.getString("msg")), now)
            assertEquals("changed, step $i", s.getBoolean("changed"), changed)
            val want = s.getJSONArray("in").let { a -> (0 until a.length()).map { keys.getString(a.getString(it)) }.sorted() }
            assertEquals("in, step $i", want, p.inRoom(g, ch, now))
        }
    }

    @Test
    fun glare() {
        val gl = r.getJSONArray("glare")
        for (i in 0 until gl.length()) {
            val x = gl.getJSONObject(i)
            assertEquals(keys.getString(x.getString("wins")), RoomLogic.offerWins(keys.getString(x.getString("a")), keys.getString(x.getString("b"))))
        }
    }

    @Test
    fun madeHereVerifies() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val me = ChatCrypto.b64(kp.public.encoded)
        val m = RoomLogic.make(kp.private, "vjoin", r.getString("g"), r.getString("groupCall"), 1761500400000L)
        assertEquals(null, RoomLogic.check(m))
        assertTrue(RoomLogic.verify(me, m))
        assertFalse(RoomLogic.verify(keys.getString("A"), m))
    }

    @Test
    fun roomOfferParses() {
        // An offer inside a room carries g and ch; Muninn hands those to the room, never rings for them.
        val s = CallLogic.parse("x", """{"t":"vring","call":"-","g":"a","ch":"b","ts":1,"sig":"x"}""")
        assertTrue(s.voiceRoom)
    }
}
