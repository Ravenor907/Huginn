package io.github.ravenor907.muninn.net

import android.content.Context
import io.github.ravenor907.muninn.Muninn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address

/**
 * Moving to another device ("Move here", cap x1, docs/protocol-0.6.md section 18). The new device shows a code
 * ([showCode]); the old one takes it ([take]), both show the same 4 emoji, the old one says go ([go]) and is locked from
 * then on: it sends everything (identity, contacts, groups, history) straight over the home Wi-Fi in sealed parts, and
 * once the new device says it has it all and took it, the old one erases its keys and shows "Moved to [name]", with one
 * tap to become a linked device of the new one ([becomeLinked]). An interrupted move goes on where it stopped (parts
 * already there aren't sent again); the old device stays locked until it's done. Moving back is the same thing again.
 *
 * Kept outside the vault (a move replaces it): "muninn-move" settings, and the parts in the cache.
 */
object Move {
    /** On the new device. [state]: "shown" (code on screen), "hello" (emoji to check), "receiving", "done". */
    data class Incoming(val state: String, val emoji: String = "", val from: String = "", val have: Int = 0, val of: Int = 0, val confirmed: Boolean = false)
    /** On the old device. [state]: "checking" (emoji to check), "moving" (locked, sending), "moved", "failed". */
    data class Outgoing(val state: String, val emoji: String = "", val to: String = "", val sent: Int = 0, val of: Int = 0, val note: String = "")

    private val _incoming = MutableStateFlow<Incoming?>(null)
    val incoming: StateFlow<Incoming?> = _incoming.asStateFlow()
    private val _outgoing = MutableStateFlow<Outgoing?>(null)
    val outgoing: StateFlow<Outgoing?> = _outgoing.asStateFlow()
    /** True from "go" until the move is done: this device can't send, read or receive as you. */
    @Volatile var locked = false
        private set

    private const val PREFS = "muninn-move"
    private const val COMMON = "@move.json"
    @Volatile private var app: Muninn? = null
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun json(c: Context, k: String) = prefs(c).getString(k, null)?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    private fun put(c: Context, k: String, o: JSONObject?) { prefs(c).edit().apply { if (o == null) remove(k) else putString(k, o.toString()) }.commit() }

    /** Muninn started (before the vault opens): an unfinished move on this device goes on, locked. */
    fun init(a: Muninn) {
        app = a
        json(a, "out")?.let { o ->
            when (o.optString("state")) {
                "moving" -> { locked = true; _outgoing.value = Outgoing("moving", o.optString("emoji"), o.optString("to")); Thread { send(a) }.start() }
                "moved" -> _outgoing.value = Outgoing("moved", to = o.optString("to"))
            }
        }
        json(a, "in")?.let { o -> _incoming.value = Incoming(o.optString("state"), o.optString("emoji"), o.optString("from"), confirmed = o.optBoolean("ok")) }
    }

    /** "Moved to [name]": this device was moved from; nothing of the person is left on it. */
    fun movedTo(c: Context): String? = json(c, "out")?.takeIf { it.optString("state") == "moved" }?.optString("to")

    // ------------------------------------------------------------------ the new device

    /** The code to show (a new one each time, or the same while a move to here is under way). */
    fun showCode(a: Muninn): String = synchronized(lock) {
        val cur = json(a, "in")
        if (cur != null && cur.optString("state") in setOf("hello", "receiving")) return cur.getString("code")
        val kp = ChatCrypto.newKeyPair()
        val secret = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val port = LocalSend.PORT
        val addrs = LocalSend.lanInterfaces().flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { "${it.hostAddress}:$port" } }
        val code = MoveWire.code(MoveWire.Code(addrs.ifEmpty { listOf("0.0.0.0:$port") }, ChatCrypto.b64(kp.public.encoded), secret,
            a.identity.deviceName.take(40), "muninn"))
        put(a, "in", JSONObject().put("state", "shown").put("code", code).put("priv", ChatCrypto.b64(kp.private.encoded))
            .put("pub", ChatCrypto.b64(kp.public.encoded)).put("s", ChatCrypto.b64(secret)))
        File(a.cacheDir, "move-in").deleteRecursively()
        _incoming.value = Incoming("shown")
        a.listen()   // the Wi-Fi side must be up to be reached
        code
    }

    /** The person on the new device saw the same 4 emoji on both screens. */
    fun confirmHere(a: Muninn) {
        val o = json(a, "in") ?: return
        put(a, "in", o.put("ok", true))
        _incoming.value = _incoming.value?.copy(confirmed = true)
        Thread { try { finishIfReady(a) } catch (_: Exception) {} }.start()
    }

    /** Stop a move to here (the code stops working). */
    fun cancelHere(a: Muninn) { put(a, "in", null); File(a.cacheDir, "move-in").deleteRecursively(); _incoming.value = null }

    /** POST [MoveWire.PATH] on this device's Wi-Fi port. */
    fun handle(body: String): Pair<Int, String> = synchronized(lock) {
        val a = app ?: return 503 to "{}"
        val p = try { JSONObject(body) } catch (e: Exception) { return 400 to "{}" }
        val s = json(a, "in") ?: return 404 to "{}"
        when (p.optString("t")) {
            "hello" -> {
                val e = p.optString("e")
                // Once parts started coming, only the same old device.
                if (s.has("old") && s.optString("old") != e && s.optString("state") != "shown") return 409 to "{}"
                val k = try { MoveWire.key(ChatCrypto.privateFrom(s.getString("priv")), e, ChatCrypto.b64d(s.getString("s")), s.getString("pub"), e) }
                    catch (x: Exception) { return 400 to "{}" }
                if (!MoveWire.same(p.optString("m"), MoveWire.helloMac(k, e))) return 403 to "{}"
                if (s.optString("old") != e) {
                    s.put("old", e).put("k", ChatCrypto.b64(k)).put("from", p.optString("n").take(40)).put("app", p.optString("app")).put("emoji", MoveWire.emoji(k))
                        .put("state", "hello").put("ok", false)
                    put(a, "in", s)
                    _incoming.value = Incoming("hello", MoveWire.emoji(k), s.optString("from"))
                }
                return 200 to JSONObject().put("m", MoveWire.readyMac(k, e)).toString()
            }
            "part" -> {
                val k = s.optString("k").ifEmpty { return 403 to "{}" }.let { ChatCrypto.b64d(it) }
                val i = p.optInt("i", -1); val n = p.optInt("n"); val h = p.optString("h")
                if (n < 1 || n > 100_000 || i !in 0 until n || h.length != 64) return 400 to "{}"
                MoveWire.open(k, p.optString("d"), MoveWire.partAad(i, n, h)) ?: return 403 to "{}"
                if (s.optString("h") != h) { File(a.cacheDir, "move-in").deleteRecursively(); s.put("h", h).put("n", n) }
                val dir = File(a.cacheDir, "move-in").also { it.mkdirs() }
                // Kept as it came (still sealed with K) until it's all here.
                File(dir, i.toString()).writeText(p.optString("d"))
                if (s.optString("state") == "hello") s.put("state", "receiving")
                put(a, "in", s)
                val have = dir.list()?.size ?: 0
                _incoming.value = _incoming.value?.copy(state = "receiving", have = have, of = n)
                return 200 to JSONObject().put("have", have).toString()
            }
            "status" -> {
                val k = s.optString("k").ifEmpty { return 403 to "{}" }.let { ChatCrypto.b64d(it) }
                val h = s.optString("h"); val n = s.optInt("n")
                val st = when {
                    s.optString("state") == "done" -> "done"
                    h.isEmpty() || missing(a, n).isNotEmpty() -> "need"
                    !s.optBoolean("ok") -> "confirm"
                    else -> { Thread { try { finishIfReady(a) } catch (_: Exception) {} }.start(); "taking" }
                }
                return 200 to JSONObject().put("state", st).put("missing", JSONArray(if (h.isEmpty()) emptyList() else missing(a, n).take(64)))
                    .put("m", MoveWire.statusMac(k, st, h)).toString()
            }
            "link" -> {
                val k = s.optString("k").ifEmpty { return 403 to "{}" }.let { ChatCrypto.b64d(it) }
                val code = MoveWire.open(k, p.optString("d"), MoveWire.LINK_AAD)?.let { String(it) } ?: return 403 to "{}"
                if (!a.ready.value) return 503 to "{}"
                val err = Link.link(code)
                if (err == null) { put(a, "in", null); _incoming.value = null }
                return (if (err == null) 200 else 400) to "{}"
            }
        }
        return 400 to "{}"
    }

    private fun missing(c: Context, n: Int): List<Int> {
        val dir = File(c.cacheDir, "move-in")
        return (0 until n).filter { !File(dir, it.toString()).exists() }
    }

    /** Everything is here and the person said the emoji match: take it in, then start again as you. */
    private fun finishIfReady(a: Muninn) { synchronized(lock) { takeIn(a) } }

    private fun takeIn(a: Muninn) {
        val s = json(a, "in") ?: return
        if (!s.optBoolean("ok") || s.optString("state") != "receiving") return
        val n = s.optInt("n"); val h = s.optString("h")
        if (n < 1 || missing(a, n).isNotEmpty()) return
        val k = ChatCrypto.b64d(s.getString("k"))
        val dir = File(a.cacheDir, "move-in")
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until n) out.write(MoveWire.open(k, File(dir, i.toString()).readText(), MoveWire.partAad(i, n, h)) ?: run { File(dir, i.toString()).delete(); return })
        val blob = out.toByteArray()
        if (MoveWire.sha256Hex(blob) != h) { dir.deleteRecursively(); return }
        val entries = MoveWire.unpack(blob) ?: return
        val files = entries.firstOrNull { it.first == COMMON }?.let { MoveCommon.toMuninn(a, JSONObject(String(it.second))) } ?: entries
        if (!a.ready.value) return
        Backup.restore(a, a.vault, BackupFormat.Opened(BackupFormat.Header(1, ByteArray(0), 0, "", "muninn"), files))
        // Done before restarting: the old device asks again and gets "done" from the restarted app.
        put(a, "in", s.put("state", "done"))
        dir.deleteRecursively()
        _incoming.value = Incoming("done", from = s.optString("from"))
        Thread { Thread.sleep(4000); Backup.restart(a) }.start()
    }

    // ------------------------------------------------------------------ the old device

    /** Takes the new device's code: says hello over the Wi-Fi. Null when the emoji are on screen, else what went wrong. */
    fun take(a: Muninn, text: String): String? {
        val c = MoveWire.parse(text) ?: return "That isn't a move code. On the new device: the Add screen, Move here."
        val kp = ChatCrypto.newKeyPair()
        val mine = ChatCrypto.b64(kp.public.encoded)
        val k = MoveWire.key(kp.private, c.e, c.s, c.e, mine)
        val hello = JSONObject().put("t", "hello").put("e", mine).put("n", a.identity.deviceName.take(40)).put("app", "muninn").put("m", MoveWire.helloMac(k, mine))
        for (addr in c.a) {
            val r = post(addr, hello.toString()) ?: continue
            if (r.first != 200) continue
            if (!MoveWire.same(try { JSONObject(r.second).optString("m") } catch (e: Exception) { "" }, MoveWire.readyMac(k, mine))) continue
            put(a, "out", JSONObject().put("state", "checking").put("addr", addr).put("all", JSONArray(c.a)).put("k", ChatCrypto.b64(k))
                .put("to", c.n).put("app", c.app).put("emoji", MoveWire.emoji(k)))
            _outgoing.value = Outgoing("checking", MoveWire.emoji(k), c.n)
            return null
        }
        return "Couldn't reach ${c.n}. Both need to be on the same Wi-Fi, with Muninn or Huginn open on the Move here screen."
    }

    /**
     * The emoji match on both screens: lock this device, pack everything up and send it. From here on this device
     * can't send, read or receive as you; [send] runs until the new device has it all.
     */
    fun go(a: Muninn): String? {
        val o = json(a, "out") ?: return "Start again from the new device's code."
        if (o.optString("state") != "checking") return null
        locked = true
        val entries = try {
            if (o.optString("app") == "muninn") Backup.entries(a, a.vault)
            else listOf(COMMON to MoveCommon.fromMuninn(a).toString().toByteArray())
        } catch (e: Exception) { locked = false; return "Couldn't gather your chats (${e.message})." }
        val blob = MoveWire.pack(entries)
        val k = ChatCrypto.b64d(o.getString("k"))
        // Kept sealed with K until the move is done, so an interrupted one goes on after a restart.
        File(a.filesDir, "move-out.bin").writeText(MoveWire.seal(k, blob, "move|out"))
        put(a, "out", o.put("state", "moving").put("h", MoveWire.sha256Hex(blob)).put("n", MoveWire.parts(blob.size.toLong())))
        _outgoing.value = Outgoing("moving", o.optString("emoji"), o.optString("to"), 0, MoveWire.parts(blob.size.toLong()))
        try { a.nostr.keepAlive = false } catch (_: Exception) {}
        Thread { send(a) }.start()
        return null
    }

    /** Stop before "go": nothing was sent. */
    fun cancelOut(a: Muninn) { if (!locked) { put(a, "out", null); _outgoing.value = null } }

    private fun send(a: Muninn) {
        val o = json(a, "out") ?: return
        val k = ChatCrypto.b64d(o.getString("k"))
        val h = o.getString("h"); val n = o.getInt("n")
        val blob = MoveWire.open(k, File(a.filesDir, "move-out.bin").readText(), "move|out") ?: return
        val addrs = listOf(o.getString("addr")) + o.optJSONArray("all").let { x -> if (x == null) emptyList() else (0 until x.length()).map { x.getString(it) } }
        var tries = 0
        while (true) {
            var st: JSONObject? = null
            for (addr in addrs.distinct()) {
                val r = post(addr, JSONObject().put("t", "status").toString()) ?: continue
                val j = try { JSONObject(r.second) } catch (e: Exception) { continue }
                // Signed with K over the state and the payload's hash (empty before the first part reached it).
                val state = j.optString("state")
                val good = MoveWire.same(j.optString("m"), MoveWire.statusMac(k, state, h)) || (state == "need" && MoveWire.same(j.optString("m"), MoveWire.statusMac(k, state, "")))
                if (r.first == 200 && good) { st = j.put("@addr", addr); break }
            }
            if (st == null) {
                _outgoing.value = _outgoing.value?.copy(note = "Waiting for ${o.optString("to")} on the Wi-Fi…")
                if (++tries > 3) Thread.sleep(5000) else Thread.sleep(1500)
                continue
            }
            val addr = st.getString("@addr")
            when (st.optString("state")) {
                "done" -> { finishMoved(a, o.optString("to")); return }
                "need" -> {
                    val m = st.optJSONArray("missing")
                    val want = if (m == null || m.length() == 0) (0 until n).toList() else (0 until m.length()).map { m.getInt(it) }
                    for (i in want) {
                        val part = blob.copyOfRange(i * MoveWire.PART, minOf(blob.size, (i + 1) * MoveWire.PART))
                        val body = JSONObject().put("t", "part").put("i", i).put("n", n).put("h", h).put("d", MoveWire.seal(k, part, MoveWire.partAad(i, n, h)))
                        val r = post(addr, body.toString())
                        if (r?.first == 200) _outgoing.value = _outgoing.value?.copy(sent = try { JSONObject(r.second).optInt("have") } catch (e: Exception) { 0 }, of = n, note = "")
                    }
                }
                "confirm" -> { _outgoing.value = _outgoing.value?.copy(sent = n, of = n, note = "Tap \"They match\" on ${o.optString("to")}."); Thread.sleep(1500) }
                else -> Thread.sleep(1500)
            }
        }
    }

    /** The new device has everything: this one's keys and chats are erased, and it says where you went. */
    private fun finishMoved(a: Muninn, to: String) {
        val o = json(a, "out") ?: JSONObject()
        a.vault.eraseAll()
        File(a.filesDir, "move-out.bin").delete()
        // Only what becoming a linked device needs: where the new device is and K (to seal the link code).
        put(a, "out", JSONObject().put("state", "moved").put("to", to).put("addr", o.optString("addr")).put("all", o.optJSONArray("all") ?: JSONArray())
            .put("k", o.optString("k")))
        locked = false
        _outgoing.value = Outgoing("moved", to = to)
        Backup.restart(a)
    }

    /** One tap on "Moved to [name]": this device starts fresh and becomes a linked device of the new one. Null when asked. */
    fun becomeLinked(a: Muninn): String? {
        val o = json(a, "out") ?: return "Nothing to link to."
        if (!a.ready.value && !(a.vault.openDefault().also { if (it) a.start() })) return "Unlock Muninn first."
        val k = ChatCrypto.b64d(o.getString("k"))
        val body = JSONObject().put("t", "link").put("d", MoveWire.seal(k, Link.myLinkCode().toByteArray(), MoveWire.LINK_AAD)).toString()
        val addrs = (listOf(o.optString("addr")) + o.optJSONArray("all").let { x -> if (x == null) emptyList() else (0 until x.length()).map { x.getString(it) } }).filter { it.isNotEmpty() }
        for (addr in addrs.distinct()) if (post(addr, body)?.first == 200) { startFresh(a); return null }
        return "Couldn't reach ${o.optString("to")}. Open Muninn or Huginn there, on the same Wi-Fi, and try again."
    }

    /** Leave "Moved to [name]" and use this device as a new one. */
    fun startFresh(c: Context) { put(c, "out", null); _outgoing.value = null }

    /** A JSON POST to [addr] ("ip:port") on the move path, on the same network only. */
    private fun post(addr: String, json: String): Pair<Int, String>? = LocalSend.postTo(addr, MoveWire.PATH, json)
}
