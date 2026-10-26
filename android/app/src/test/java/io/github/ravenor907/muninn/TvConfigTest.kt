package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.ChatCrypto
import io.github.ravenor907.muninn.tv.Canon
import io.github.ravenor907.muninn.tv.Quick
import io.github.ravenor907.muninn.tv.TvPerson
import io.github.ravenor907.muninn.tv.TvRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The kids' TV against tests/proto06-vectors.json ("canonical" and "tv"): Huginn and the reference agree. */
class TvConfigTest {
    private val all = JSONObject(javaClass.classLoader!!.getResource("proto06-vectors.json")!!.readText(Charsets.UTF_8))
    private val chat = JSONObject(javaClass.classLoader!!.getResource("chat-vectors.json")!!.readText(Charsets.UTF_8))
    private val tv = all.getJSONObject("tv")
    private val keys = all.getJSONObject("keys")
    private val kA = keys.getString("A")
    private val kB = keys.getString("B")
    private val kTV = keys.getString("TV")

    private fun cfg(name: String): JSONObject = tv.getJSONObject("configs").getJSONObject(name).getJSONObject("config")

    @Test
    fun canonicalForm() {
        val c = all.getJSONObject("canonical")
        val good = c.getJSONArray("good")
        for (i in 0 until good.length()) {
            val g = good.getJSONObject(i)
            assertEquals(g.getString("canonical"), Canon.of(g.get("value")))
        }
        val refuse = c.getJSONArray("refuse")
        for (i in 0 until refuse.length()) {
            val text = refuse.getString(i)
            try { Canon.of(JSONObject(text)); fail("should refuse $text") } catch (e: IllegalArgumentException) {}
        }
    }

    @Test
    fun configsSignAndHashLikeTheReference() {
        val configs = tv.getJSONObject("configs")
        for (name in configs.keys()) {
            val e = configs.getJSONObject(name)
            val c = e.getJSONObject("config")
            assertEquals(name, e.getString("signed"), String(Canon.signedBytes(TvRules.CONFIG, c), Charsets.UTF_8))
            assertEquals(name, e.getString("hash"), Canon.hash(c))
            assertTrue(name, Canon.verify(c.getString("by"), TvRules.CONFIG, c))
            TvRules.check(c)   // every config in the file is well-formed
        }
        val q = TvRules.parse(cfg("cfg2")).quick.map { it.text }
        assertEquals(listOf("I love you", "I'm hungry", "Can you come here?"), q)   // array order is the order on screen
    }

    @Test
    fun acceptsAndRefusesInOrder() {
        val acc = tv.getJSONArray("accept")
        assertEquals(6, acc.length())
        for (i in 0 until acc.length()) {
            val a = acc.getJSONObject(i)
            val parents = a.getJSONArray("parents").let { p -> (0 until p.length()).map { if (p.getString(it) == "A") kA else kB } }
            val cur = if (a.isNull("current")) null else cfg(a.getString("current"))
            val newName = a.getString("new")
            val new = if (newName.startsWith("cfg2 with dictation")) JSONObject(cfg("cfg2").toString()).put("dictation", false) else cfg(newName)
            val (got, why) = TvRules.accept(kTV, parents, cur, new)
            assertEquals("step $i: $why", a.getBoolean("accept"), got)
            assertEquals("step $i", a.getString("why"), why)
        }
    }

    @Test
    fun disconnectOnlyForTheCurrentVersion() {
        val d = tv.getJSONObject("disconnect")
        val msg = d.getJSONObject("msg")
        assertEquals(d.getString("signed"), String(Canon.signedBytes(TvRules.DISCONNECT, msg), Charsets.UTF_8))
        assertTrue(TvRules.acceptDisconnect(kTV, listOf(kA, kB), d.getLong("acceptWhenCurrentV"), msg).first)
        assertFalse(TvRules.acceptDisconnect(kTV, listOf(kA, kB), d.getLong("refuseWhenCurrentV"), msg).first)
        assertFalse(TvRules.acceptDisconnect(kTV, listOf(kA), 3, msg).first)   // not from a parent
        assertFalse(TvRules.acceptDisconnect(kA, listOf(kA, kB), 3, msg).first)   // for another TV
        // A config's signature never works as a disconnect: the domains differ.
        val asConfig = Canon.sign(ChatCrypto.privateFrom(chat.getString("privB")), TvRules.CONFIG, Canon.unsigned(msg))
        assertFalse(TvRules.acceptDisconnect(kTV, listOf(kA, kB), 3, asConfig).first)
    }

    @Test
    fun whatAParentBuildsTheTvTakes() {
        val privA = ChatCrypto.privateFrom(chat.getString("privA"))
        val quick = TvRules.DEFAULT_QUICK.map { Quick(TvRules.quickId(), it) }
        val people = listOf(TvPerson(kB, "Grandma", "b".repeat(64)), TvPerson(kA, "Dad", "a".repeat(64)))
        val built = TvRules.config(privA, kTV, 1, kA, listOf(kA), quick, people, true, 1761500000000)
        TvRules.check(built)
        // Through the wire: org.json text, then read back as the TV does.
        val wire = JSONObject(TvRules.configMessage(built))
        assertEquals("config", wire.getString("t"))
        val got = wire.getJSONObject("cfg")
        assertEquals(Canon.of(built), Canon.of(got))
        assertEquals(true to "newer", TvRules.accept(kTV, listOf(kA), null, got))
        val parsed = TvRules.parse(got)
        assertEquals(TvRules.DEFAULT_QUICK, parsed.quick.map { it.text })
        assertEquals(listOf(kA, kB).sorted(), parsed.contacts.map { it.k })
        assertTrue(parsed.dictation)
        // The next version from the same parent replaces it; the same one again doesn't.
        val v2 = TvRules.config(privA, kTV, 2, kA, listOf(kA), quick.reversed(), people, false, 1761500000001)
        assertTrue(TvRules.accept(kTV, listOf(kA), got, v2).first)
        assertFalse(TvRules.accept(kTV, listOf(kA), v2, v2).first)
        // Disconnect built here verifies too.
        val d = TvRules.disconnect(privA, kTV, 2, kA, 1761500000002)
        assertTrue(TvRules.acceptDisconnect(kTV, listOf(kA), 2, JSONObject(TvRules.disconnectMessage(d)).getJSONObject("d")).first)
    }

    @Test
    fun refusesMalformedConfigs() {
        val good = cfg("cfg2")
        fun bad(why: String, f: (JSONObject) -> Unit) {
            val c = JSONObject(good.toString()); f(c)
            try { TvRules.check(c); fail("should refuse: $why") } catch (e: IllegalArgumentException) {}
        }
        bad("unsorted parents") { it.put("parents", JSONArray().put(kA).put(kB).let { a -> if (kA < kB) JSONArray().put(kB).put(kA) else a }) }
        bad("signer not a parent") { it.put("by", keys.getString("C")) }
        bad("extra field") { it.put("x", 1) }
        bad("missing field") { it.remove("ts") }
        bad("version 0") { it.put("v", 0) }
        bad("dictation not true or false") { it.put("dictation", 1) }
        bad("long quick message") { it.getJSONArray("quick").getJSONObject(0).put("text", "a".repeat(81)) }
        bad("blank quick message") { it.getJSONArray("quick").getJSONObject(0).put("text", "   ") }
        bad("control character") { it.getJSONArray("quick").getJSONObject(0).put("text", "a\nb") }
        bad("short quick id") { it.getJSONArray("quick").getJSONObject(0).put("id", "AAAA") }
        bad("repeated quick id") { val q = it.getJSONArray("quick"); q.put(JSONObject(q.getJSONObject(0).toString())) }
        bad("too many quick messages") { val q = JSONArray(); repeat(25) { q.put(JSONObject().put("id", TvRules.quickId()).put("text", "hi")) }; it.put("quick", q) }
        bad("long name") { it.getJSONArray("contacts").getJSONObject(0).put("n", "n".repeat(41)) }
        bad("contacts unsorted") { val c = it.getJSONArray("contacts"); it.put("contacts", JSONArray().put(c.get(1)).put(c.get(0))) }
        // 80 characters counted as code points: 80 runes are fine.
        val ok = JSONObject(good.toString()); ok.getJSONArray("quick").getJSONObject(0).put("text", "ᚺ".repeat(80)); TvRules.check(ok)
    }
}
