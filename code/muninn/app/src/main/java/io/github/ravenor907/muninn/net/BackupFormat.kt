package io.github.ravenor907.muninn.net

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup file, shared with Huginn. Plain Kotlin (no Android) so it can be tested off the phone.
 *
 *   "MUNB1"
 *   uint32 BE header length, then the header JSON: {"v":1,"salt":base64,"rounds":310000,"created":ISO8601,"app":"muninn"}
 *   12-byte IV + AES-256-GCM ciphertext + 16-byte tag (the vault's own layout)
 *
 * Key: PBKDF2-SHA256(password, salt, rounds), 32 bytes. Inside the encryption, the files one after
 * another: [uint32 BE name length][name, UTF-8][uint32 BE data length][data]. Nothing but the header
 * is readable without the password: not the names, not how many files there are.
 */
object BackupFormat {
    val MAGIC = "MUNB1".toByteArray()
    const val VERSION = 1
    const val ROUNDS = 310_000
    private const val MAX_HEADER = 4096
    private const val MAX_NAME = 4096
    private val random = SecureRandom()

    class Header(val v: Int, val salt: ByteArray, val rounds: Int, val created: String, val app: String)
    /** A file to put in the backup. [load] gives its plain bytes, or null to leave it out. */
    class Entry(val name: String, val load: () -> ByteArray?)
    class Opened(val header: Header, val files: List<Pair<String, ByteArray>>)

    class NotABackup(message: String) : Exception(message)
    class WrongPassword : Exception("Wrong password")

    /** Writes a whole backup to [out] (and closes it). [progress] goes from 0 to 1 by files done. */
    fun write(out: OutputStream, password: String, entries: List<Entry>, app: String = "muninn",
              created: String = java.time.Instant.now().toString(), rounds: Int = ROUNDS,
              salt: ByteArray = ByteArray(16).also { random.nextBytes(it) }, progress: (Float) -> Unit = {}): Int {
        val key = derive(password, salt, rounds)
        val header = "{\"v\":$VERSION,\"salt\":\"${b64(salt)}\",\"rounds\":$rounds,\"created\":\"${esc(created)}\",\"app\":\"${esc(app)}\"}".toByteArray()
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        key.fill(0)
        out.write(MAGIC)
        out.write(ByteBuffer.allocate(4).putInt(header.size).array())
        out.write(header)
        out.write(iv)
        var count = 0
        DataOutputStream(BufferedOutputStream(CipherOutputStream(out, c), 64 * 1024)).use { d ->
            entries.forEachIndexed { i, e ->
                val data = e.load()
                if (data != null) {
                    val name = e.name.toByteArray(Charsets.UTF_8)
                    d.writeInt(name.size); d.write(name)
                    d.writeInt(data.size); d.write(data)
                    count++
                }
                progress((i + 1f) / entries.size)
            }
        }
        if (entries.isEmpty()) progress(1f)
        return count
    }

    /** Reads the header only (no password needed). Returns it and where the encrypted part starts. */
    fun header(bytes: ByteArray): Pair<Header, Int> {
        if (bytes.size < MAGIC.size + 4 || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw NotABackup("This isn't a Muninn backup.")
        val len = ByteBuffer.wrap(bytes, MAGIC.size, 4).int
        if (len <= 0 || len > MAX_HEADER || MAGIC.size + 4 + len > bytes.size) throw NotABackup("This backup is damaged.")
        val json = String(bytes, MAGIC.size + 4, len, Charsets.UTF_8)
        val fields = Regex("\"([A-Za-z]+)\"\\s*:\\s*(?:\"([^\"\\\\]*)\"|(-?\\d+))").findAll(json)
            .associate { m -> m.groupValues[1] to (m.groups[2]?.value ?: m.groupValues[3]) }
        val v = fields["v"]?.toIntOrNull() ?: throw NotABackup("This backup is damaged.")
        if (v != VERSION) throw NotABackup("This backup is from a newer version. Update Muninn first.")
        val salt = try { b64d(fields["salt"] ?: "") } catch (e: Exception) { ByteArray(0) }
        val rounds = fields["rounds"]?.toIntOrNull() ?: 0
        // Rounds come from the unprotected header: refuse a weakened one rather than trust it.
        if (salt.size < 16 || rounds < 100_000 || rounds > 10_000_000) throw NotABackup("This backup is damaged.")
        return Header(v, salt, rounds, fields["created"] ?: "", fields["app"] ?: "") to (MAGIC.size + 4 + len)
    }

    /** Checks the password and unpacks every file. Throws [WrongPassword] or [NotABackup]. */
    fun open(bytes: ByteArray, password: String, app: String = "muninn"): Opened {
        val (h, at) = header(bytes)
        if (h.app != app) throw NotABackup(if (h.app == "huginn") "This is a Huginn backup. Restore it in Huginn." else "This isn't a Muninn backup.")
        if (bytes.size - at < 12 + 16) throw NotABackup("This backup is damaged.")
        val key = derive(password, h.salt, h.rounds)
        val plain = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes, at, 12))
            c.doFinal(bytes, at + 12, bytes.size - at - 12)
        } catch (e: AEADBadTagException) {
            throw WrongPassword()   // a wrong password and a changed file look the same, by design
        } finally { key.fill(0) }
        return Opened(h, unpack(plain))
    }

    fun unpack(plain: ByteArray): List<Pair<String, ByteArray>> {
        val b = ByteBuffer.wrap(plain)
        val out = ArrayList<Pair<String, ByteArray>>()
        while (b.hasRemaining()) {
            if (b.remaining() < 4) throw NotABackup("This backup is damaged.")
            val n = b.int
            if (n <= 0 || n > MAX_NAME || n > b.remaining()) throw NotABackup("This backup is damaged.")
            val name = ByteArray(n).also { b.get(it) }.toString(Charsets.UTF_8)
            if (b.remaining() < 4) throw NotABackup("This backup is damaged.")
            val len = b.int
            if (len < 0 || len > b.remaining()) throw NotABackup("This backup is damaged.")
            out += name to ByteArray(len).also { b.get(it) }
        }
        return out
    }

    /** A name that stays inside the data folder: relative, no "..", no backslashes. */
    fun safeName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && !name.contains('\\') && !name.contains('\u0000') &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, rounds, 256)).encoded

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    private fun b64d(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
}
