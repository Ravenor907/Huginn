package io.github.ravenor907.muninn.net.tor

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Tor's control protocol (control-spec.txt), just the parts Muninn uses: AUTHENTICATE, SETEVENTS,
 * GETINFO and ADD_ONION. Kept free of Android so it can be tested off the phone.
 *
 * A reply is one or more lines "CODE-text" ... "CODE text": a dash means more lines follow, a
 * space ends the reply, and "CODE+key=" starts a data block that runs until a line with only ".".
 * Codes starting with 6 are events sent by themselves; everything else answers the last command.
 */
data class Reply(val status: Int, val lines: List<String>) {
    val ok get() = status == 250
    val isEvent get() = status in 600..699
    /** The first line's text, for events: "STATUS_CLIENT NOTICE BOOTSTRAP PROGRESS=10 ...". */
    val text get() = lines.firstOrNull() ?: ""

    /** "key=value" lines as a map (ServiceID, PrivateKey, GETINFO answers). */
    fun values(): Map<String, String> = lines.filter { it.contains('=') }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
}

object ControlProtocol {
    /** Reads one whole reply, or null at the end of the stream. */
    fun read(readLine: () -> String?): Reply? {
        val lines = ArrayList<String>()
        var status = 0
        while (true) {
            val line = readLine() ?: return null
            if (line.length < 4) throw IOException("Bad control line: $line")
            status = line.substring(0, 3).toIntOrNull() ?: throw IOException("Bad control line: $line")
            val rest = line.substring(4)
            when (line[3]) {
                ' ' -> { lines += rest; return Reply(status, lines) }
                '-' -> lines += rest
                '+' -> {
                    val data = StringBuilder()
                    while (true) {
                        val d = readLine() ?: return null
                        if (d == ".") break
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(if (d.startsWith("..")) d.substring(1) else d)
                    }
                    lines += rest + data
                }
                else -> throw IOException("Bad control line: $line")
            }
        }
    }

    /** PROGRESS=NN from a bootstrap status line, or null if it isn't one. */
    fun bootstrapProgress(text: String): Int? {
        if (!text.contains("BOOTSTRAP")) return null
        return Regex("""\bPROGRESS=(\d{1,3})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)
    }

    /** A STATUS_CLIENT event's action, such as "BOOTSTRAP" or "CIRCUIT_ESTABLISHED". */
    fun clientStatus(text: String): String? {
        val parts = text.split(' ')
        return if (parts.size >= 3 && parts[0] == "STATUS_CLIENT") parts[2] else null
    }

    /** The port of the first listener in a GETINFO net/listeners/socks answer ("127.0.0.1:9050" "[::1]:9050"). */
    fun listenerPort(value: String): Int? =
        Regex(""""?[^"\s]*:(\d{1,5})"?""").find(value.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..65535 }

    /** The ADD_ONION command: an existing key, or a new one (Tor answers with its PrivateKey). */
    fun addOnion(key: String?, virtualPort: Int, localPort: Int): String {
        require(key == null || OnionKey.validKey(key)) { "Not an ED25519-V3 key" }
        return "ADD_ONION ${key ?: "NEW:ED25519-V3"} Port=$virtualPort,127.0.0.1:$localPort"
    }

    /** Quotes a string for the control protocol. */
    fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * One control connection. A reader thread splits replies from events; commands wait for their
 * reply. [onClosed] runs once when the connection ends (Tor stopped, or [close] was called).
 */
class ControlConnection(
    input: InputStream,
    private val output: OutputStream,
    private val onEvent: (Reply) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private val replies = LinkedBlockingQueue<Reply>()
    @Volatile var closed = false
        private set
    private val lock = Object()

    private val thread = Thread({
        try {
            while (true) {
                val r = ControlProtocol.read { reader.readLine() } ?: break
                if (r.isEvent) try { onEvent(r) } catch (_: Exception) {} else replies.put(r)
            }
        } catch (_: Exception) {
        } finally {
            closed = true
            replies.offer(Reply(-1, emptyList()))   // wakes a waiting command
            try { onClosed() } catch (_: Exception) {}
        }
    }, "muninn-torctl").apply { isDaemon = true }

    fun start(): ControlConnection { thread.start(); return this }

    /** Sends one command and waits for its reply; throws if the connection is gone or slow. */
    fun command(line: String, timeoutMs: Long = 20_000): Reply = synchronized(lock) {
        if (closed) throw IOException("Tor control is closed")
        require(!line.contains('\n') && !line.contains('\r'))
        replies.clear()
        output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
        val r = replies.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw IOException("Tor didn't answer: ${line.substringBefore(' ')}")
        if (r.status < 0) throw IOException("Tor control is closed")
        r
    }

    fun close() {
        closed = true
        try { output.close() } catch (_: Exception) {}
        try { reader.close() } catch (_: Exception) {}
    }
}
