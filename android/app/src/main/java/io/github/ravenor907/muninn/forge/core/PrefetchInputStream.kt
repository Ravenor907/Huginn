package io.github.ravenor907.muninn.forge.core

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads [inner] on its own thread, a few chunks ahead of whoever reads this stream. Used so
 * unpacking an image (CPU) and writing it to the USB drive (I/O) happen at the same time
 * instead of taking turns. Errors from [inner] surface on the next read here.
 */
class PrefetchInputStream(
    private val inner: InputStream,
    private val chunk: Int = 4 shl 20,
    depth: Int = 3,
) : InputStream() {
    private class Chunk(val data: ByteArray, val len: Int, val end: Boolean = false, val error: Throwable? = null)

    private val queue = ArrayBlockingQueue<Chunk>(depth)
    @Volatile private var closed = false
    private var cur: Chunk? = null
    private var pos = 0
    private var finished = false
    private var failure: IOException? = null

    private val worker = Thread({
        try {
            while (!closed) {
                val b = ByteArray(chunk)
                var n = 0
                while (n < chunk) {
                    val r = inner.read(b, n, chunk - n)
                    if (r < 0) break
                    n += r
                }
                if (n > 0) put(Chunk(b, n))
                if (n < chunk) {
                    put(Chunk(b, 0, end = true))
                    return@Thread
                }
            }
        } catch (t: Throwable) {
            if (!closed) put(Chunk(ByteArray(0), 0, error = t))
        }
    }, "prefetch").apply {
        isDaemon = true
        start()
    }

    private fun put(c: Chunk) {
        while (!closed) {
            try {
                if (queue.offer(c, 200, TimeUnit.MILLISECONDS)) return
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        failure?.let { throw it }
        while (true) {
            if (finished) return -1
            val c = cur
            if (c != null && pos < c.len) {
                val n = minOf(len, c.len - pos)
                System.arraycopy(c.data, pos, b, off, n)
                pos += n
                return n
            }
            val next = queue.take()
            next.error?.let { e ->
                val io = e as? IOException ?: IOException(e.message ?: e.javaClass.simpleName, e)
                failure = io
                throw io
            }
            if (next.end) {
                finished = true
                return -1
            }
            cur = next
            pos = 0
        }
    }

    override fun close() {
        closed = true
        worker.interrupt()
        queue.clear()
        inner.close()
    }
}
