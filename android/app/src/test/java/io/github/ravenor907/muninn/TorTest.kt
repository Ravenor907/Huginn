package io.github.ravenor907.muninn

import io.github.ravenor907.muninn.net.Vault
import io.github.ravenor907.muninn.net.tor.ControlConnection
import io.github.ravenor907.muninn.net.tor.ControlProtocol
import io.github.ravenor907.muninn.net.tor.HttpRequest
import io.github.ravenor907.muninn.net.tor.OnionAddress
import io.github.ravenor907.muninn.net.tor.OnionKey
import io.github.ravenor907.muninn.net.tor.Reply
import io.github.ravenor907.muninn.update.Updater
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.KeyPairGenerator
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class TorTest {
    private fun reader(text: String): () -> String? {
        val lines = text.split("\r\n").toMutableList()
        if (lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return { if (lines.isEmpty()) null else lines.removeAt(0) }
    }

    // ------------------------------------------------------------------ onion addresses

    @Test fun knownOnionAddressIsValid() {
        // DuckDuckGo's published v3 onion address.
        assertTrue(OnionAddress.valid("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"))
        assertEquals("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad",
            OnionAddress.normalize("DuckDuckGoGG42XJOC72X3SJASOWOARFBGCMVFIMAFTT6TWAGSWZCZAD.onion"))
    }

    @Test fun addressFromAKeyRoundTrips() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pub = kp.public.encoded.takeLast(32).toByteArray()   // X.509 wrapping, then the raw 32-byte key
        val id = OnionAddress.fromPublicKey(pub)
        assertEquals(56, id.length)
        assertTrue(id.endsWith("d"))   // version 3
        assertTrue(OnionAddress.valid(id))
        assertArrayEquals(pub, OnionAddress.base32Decode(id)!!.copyOfRange(0, 32))
    }

    @Test fun damagedAddressesAreRefused() {
        val good = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"
        assertFalse(OnionAddress.valid(good.replaceRange(10, 11, if (good[10] == 'a') "b" else "a")))   // checksum breaks
        assertFalse(OnionAddress.valid(good.dropLast(1)))
        assertFalse(OnionAddress.valid(good.uppercase()))
        assertFalse(OnionAddress.valid("facebookcorewwwi"))    // old v2 style
        assertNull(OnionAddress.normalize("192.168.1.5"))
        assertNull(OnionAddress.normalize("$good.onion.example.com"))
    }

    // ------------------------------------------------------------------ the saved key

    private fun newKey() = OnionKey.PREFIX + java.util.Base64.getEncoder().encodeToString(ByteArray(64) { (it * 7).toByte() })

    @Test fun onionKeyRecordRoundTripsThroughTheVault() {
        val rec = OnionKey(newKey(), "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad")
        val dataKey = ByteArray(32) { 9 }
        val sealed = Vault.Crypto.seal(dataKey, rec.encode())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("ED25519"))   // nothing readable on disk
        assertEquals(rec, OnionKey.decode(Vault.Crypto.open(dataKey, sealed)))
        assertNull(Vault.Crypto.open(ByteArray(32) { 1 }, sealed))
    }

    @Test fun badKeyRecordsAreRefused() {
        assertNull(OnionKey.decode(null))
        assertNull(OnionKey.decode("not json".toByteArray()))
        assertNull(OnionKey.decode("""{"v":1,"key":"RSA1024:abc","id":"duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"}""".toByteArray()))
        assertNull(OnionKey.decode("""{"v":1,"key":"${newKey()}","id":"nope"}""".toByteArray()))
        assertFalse(OnionKey.validKey(OnionKey.PREFIX + java.util.Base64.getEncoder().encodeToString(ByteArray(32))))
        assertTrue(OnionKey.validKey(newKey()))
    }

    // ------------------------------------------------------------------ control protocol

    @Test fun readsSingleMultiAndDataReplies() {
        val r = reader("250 OK\r\n" +
            "250-ServiceID=duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad\r\n250-PrivateKey=${newKey()}\r\n250 OK\r\n" +
            "250+info/names=\r\nversion -- The tor version\r\n..dotted\r\n.\r\n250 OK\r\n" +
            "552 Unrecognized key\r\n")
        assertEquals(Reply(250, listOf("OK")), ControlProtocol.read(r))
        val add = ControlProtocol.read(r)!!
        assertTrue(add.ok)
        assertEquals("duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad", add.values()["ServiceID"])
        assertTrue(OnionKey.validKey(add.values()["PrivateKey"]!!))
        val data = ControlProtocol.read(r)!!
        assertEquals("info/names=version -- The tor version\n.dotted", data.lines[0])
        val bad = ControlProtocol.read(r)!!
        assertEquals(552, bad.status); assertFalse(bad.ok)
        assertNull(ControlProtocol.read(r))
    }

    @Test fun readsBootstrapAndClientStatus() {
        val ev = ControlProtocol.read(reader("650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=45 TAG=requesting_descriptors SUMMARY=\"Asking for relay descriptors\"\r\n"))!!
        assertTrue(ev.isEvent)
        assertEquals("BOOTSTRAP", ControlProtocol.clientStatus(ev.text))
        assertEquals(45, ControlProtocol.bootstrapProgress(ev.text))
        assertEquals(100, ControlProtocol.bootstrapProgress("NOTICE BOOTSTRAP PROGRESS=100 TAG=done SUMMARY=\"Done\""))
        assertNull(ControlProtocol.bootstrapProgress("NOTICE CIRCUIT_ESTABLISHED"))
        assertEquals("CIRCUIT_ESTABLISHED", ControlProtocol.clientStatus("STATUS_CLIENT NOTICE CIRCUIT_ESTABLISHED"))
        assertEquals("CIRCUIT_NOT_ESTABLISHED", ControlProtocol.clientStatus("STATUS_CLIENT NOTICE CIRCUIT_NOT_ESTABLISHED REASON=CLOCK_JUMPED"))
        assertNull(ControlProtocol.clientStatus("CIRC 5 BUILT"))
    }

    @Test fun readsSocksListener() {
        assertEquals(9050, ControlProtocol.listenerPort("\"127.0.0.1:9050\""))
        assertEquals(41234, ControlProtocol.listenerPort("\"127.0.0.1:41234\" \"[::1]:41234\""))
        assertNull(ControlProtocol.listenerPort(""))
        assertNull(ControlProtocol.listenerPort("\"127.0.0.1:99999\""))
    }

    @Test fun addOnionCommandIsLocalOnly() {
        assertEquals("ADD_ONION NEW:ED25519-V3 Port=80,127.0.0.1:40000", ControlProtocol.addOnion(null, 80, 40000))
        val k = newKey()
        assertEquals("ADD_ONION $k Port=80,127.0.0.1:40000", ControlProtocol.addOnion(k, 80, 40000))
        assertEquals("\"a\\\"b\\\\c\"", ControlProtocol.quote("a\"b\\c"))
    }

    @Test fun connectionSplitsEventsFromReplies() {
        val toTor = ByteArrayOutputStream()
        val fromTor = PipedOutputStream()
        val events = LinkedBlockingQueue<Reply>()
        val closed = LinkedBlockingQueue<Boolean>()
        val c = ControlConnection(PipedInputStream(fromTor, 1 shl 16), toTor, { events.put(it) }, { closed.put(true) }).start()
        // Tor sends an event before the answer: the command still gets its own reply.
        Thread {
            Thread.sleep(100)
            fromTor.write("650 STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=5 TAG=conn SUMMARY=\"x\"\r\n250-net/listeners/socks=\"127.0.0.1:9150\"\r\n250 OK\r\n".toByteArray())
            fromTor.flush()
        }.start()
        val r = c.command("GETINFO net/listeners/socks")
        assertEquals("GETINFO net/listeners/socks\r\n", toTor.toString())
        assertEquals(9150, ControlProtocol.listenerPort(r.values()["net/listeners/socks"]!!))
        assertEquals(5, ControlProtocol.bootstrapProgress(events.poll(2, TimeUnit.SECONDS)!!.text))
        fromTor.close()
        assertEquals(true, closed.poll(2, TimeUnit.SECONDS))
    }

    // ------------------------------------------------------------------ the onion's HTTP listener

    @Test fun readsPostWithLengthOrChunks() {
        val body = """{"t":"msg"}"""
        val a = HttpRequest.read(ByteArrayInputStream(("POST /api/huginn/v1/message HTTP/1.1\r\nHost: x.onion\r\nContent-Length: ${body.length}\r\n\r\n$body").toByteArray()), 1000)!!
        assertEquals("POST", a.method); assertEquals("/api/huginn/v1/message", a.path); assertEquals(body, String(a.body!!))
        val b = HttpRequest.read(ByteArrayInputStream("POST /p HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n{\"t\"\r\n7\r\n:\"msg\"}\r\n0\r\n\r\n".toByteArray()), 1000)!!
        assertEquals(body, String(b.body!!))
        val big = HttpRequest.read(ByteArrayInputStream("POST /p HTTP/1.1\r\nContent-Length: 5000\r\n\r\n".toByteArray()), 1000)!!
        assertNull(big.body)   // too big: answered 413, never read
        assertNull(HttpRequest.read(ByteArrayInputStream("hello\r\n\r\n".toByteArray()), 1000))
    }

    // ------------------------------------------------------------------ updater fallback

    @Test fun readsTagFromLatestLink() {
        assertEquals("v0.6", Updater.tagFromLatest("https://github.com/Ravenor907/Huginn-Muninn/releases/tag/v0.6"))
        assertEquals("v0.61", Updater.tagFromLatest("https://github.com/Ravenor907/Huginn-Muninn/releases/tag/v0.61?x=1"))
        assertNull(Updater.tagFromLatest("https://github.com/Ravenor907/Huginn-Muninn/releases"))
    }

    // ------------------------------------------------------------------ no direct internet

    /**
     * The rule: nothing reaches the internet except through Tor. Only the Wi-Fi code (LocalSend,
     * same network) may open plain connections, and only Tor.kt may build an OkHttp client.
     */
    @Test fun nothingConnectsAroundTor() {
        val src = File("src/main/java").takeIf { it.isDirectory } ?: File("app/src/main/java")
        val offenders = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val text = f.readText()
            buildList {
                if (f.name != "LocalSend.kt" && (text.contains("openConnection(") || text.contains("HttpURLConnection"))) add("${f.name}: HttpURLConnection")
                if (f.name != "Tor.kt" && text.contains("OkHttpClient.Builder(")) add("${f.name}: own OkHttp client")
                if (text.contains("InetAddress.getByName(") && f.name !in setOf("LocalSend.kt", "Direct.kt")) add("${f.name}: DNS lookup")
                if (text.contains("WebView")) add("${f.name}: WebView")
            }
        }.toList()
        assertEquals(emptyList<String>(), offenders)
    }
}
