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
 *   uint32 BE header length, then the header JSON
 *   12-byte IV + AES-256-GCM ciphertext + 16-byte tag (the vault's own layout)
 *
 * The header says how the password makes the 32-byte key:
 *   {"v":2,"salt":base64,"kdf":"scrypt","N":65536,"r":8,"p":1,"created":ISO8601,"app":"muninn"}   scrypt, like the lock
 *   {"v":2,...,"kdf":"argon2id","m","t","p",...}   Argon2id (what Huginn writes; Huginn's backups only open in Huginn)
 *   {"v":1,"salt":base64,"rounds":310000,"created":ISO8601,"app":"muninn"}   PBKDF2-SHA256, still restored
 * New backups are v2 with scrypt and need a password of at least [MIN_PASSWORD] characters. Inside the encryption,
 * the files one after another: [uint32 BE name length][name, UTF-8][uint32 BE data length][data]. Nothing but the
 * header is readable without the password: not the names, not how many files there are.
 */
object BackupFormat {
    val MAGIC = "MUNB1".toByteArray()
    const val VERSION = 2
    const val ROUNDS = 310_000
    /** New backups: at least this many characters. */
    const val MIN_PASSWORD = 12
    private const val MAX_HEADER = 4096
    private const val MAX_NAME = 4096
    private val random = SecureRandom()

    /** [v] 1: PBKDF2 with [rounds]. [v] 2: [kdf] "scrypt" with [n], [r], [p], or "argon2id" with [m], [t], [p]. */
    class Header(val v: Int, val salt: ByteArray, val rounds: Int, val created: String, val app: String,
                 val kdf: String = "", val n: Int = 0, val r: Int = 0, val p: Int = 0, val m: Int = 0, val t: Int = 0)
    /** A file to put in the backup. [load] gives its plain bytes, or null to leave it out. */
    class Entry(val name: String, val load: () -> ByteArray?)
    class Opened(val header: Header, val files: List<Pair<String, ByteArray>>)

    class NotABackup(message: String) : Exception(message)
    class WrongPassword : Exception("Wrong password")

    /**
     * Writes a whole backup to [out] (and closes it). [progress] goes from 0 to 1 by files done. scrypt with the lock's
     * settings ([Vault.SCRYPT_N]...); [rounds] > 0 writes a PBKDF2 (v1) backup instead, only so tests can check it still opens.
     */
    fun write(out: OutputStream, password: String, entries: List<Entry>, app: String = "muninn",
              created: String = java.time.Instant.now().toString(), rounds: Int = 0,
              salt: ByteArray = ByteArray(16).also { random.nextBytes(it) }, progress: (Float) -> Unit = {}): Int {
        val h = if (rounds > 0) Header(1, salt, rounds, created, app)
            else Header(VERSION, salt, 0, created, app, kdf = "scrypt", n = Vault.SCRYPT_N, r = Vault.SCRYPT_R, p = Vault.SCRYPT_P)
        val key = key(h, password)
        val header = (if (h.v == 1) "{\"v\":1,\"salt\":\"${b64(salt)}\",\"rounds\":$rounds,"
            else "{\"v\":$VERSION,\"salt\":\"${b64(salt)}\",\"kdf\":\"scrypt\",\"N\":${h.n},\"r\":${h.r},\"p\":${h.p},") +
            "\"created\":\"${esc(created)}\",\"app\":\"${esc(app)}\"}"
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        key.fill(0)
        val hb = header.toByteArray()
        out.write(MAGIC)
        out.write(ByteBuffer.allocate(4).putInt(hb.size).array())
        out.write(hb)
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
        if (v != 1 && v != 2) throw NotABackup("This backup is from a newer version. Update Muninn first.")
        val salt = try { b64d(fields["salt"] ?: "") } catch (e: Exception) { ByteArray(0) }
        fun num(k: String) = fields[k]?.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: 0
        val h = Header(v, salt, num("rounds"), fields["created"] ?: "", fields["app"] ?: "", fields["kdf"] ?: "",
            num("N"), num("r"), num("p"), num("m"), num("t"))
        // The key's settings come from the unprotected header: a weakened one is refused rather than trusted, and so is
        // one so big it would run the phone out of memory.
        if (salt.size < 16 || !sane(h)) throw NotABackup("This backup is damaged.")
        return h to (MAGIC.size + 4 + len)
    }

    private fun sane(h: Header): Boolean = when {
        h.v == 1 -> h.rounds in 100_000..10_000_000
        h.kdf == "scrypt" -> h.n in 65_536..1_048_576 && (h.n and (h.n - 1)) == 0 && h.r in 8..32 && h.p in 1..16 &&
            h.n.toLong() * h.r * 128 <= (1L shl 30)
        h.kdf == "argon2id" -> h.m in 65_536..1_048_576 && h.t in 3..16 && h.p in 1..16
        else -> false
    }

    /** The key [h] says to make from [password]. Muninn makes PBKDF2 and scrypt keys; Argon2id is Huginn's. */
    fun key(h: Header, password: String): ByteArray = when {
        h.v == 1 -> derive(password, h.salt, h.rounds)
        h.kdf == "scrypt" -> Vault.Crypto.scrypt(password.toByteArray(Charsets.UTF_8), h.salt, h.n, h.r, h.p)
        else -> throw NotABackup("This backup is damaged.")
    }

    /** Checks the password and unpacks every file. Throws [WrongPassword] or [NotABackup]. */
    fun open(bytes: ByteArray, password: String, app: String = "muninn"): Opened {
        val (h, at) = header(bytes)
        if (h.app != app) throw NotABackup(if (h.app == "huginn") "This is a Huginn backup. Restore it in Huginn." else "This isn't a Muninn backup.")
        if (bytes.size - at < 12 + 16) throw NotABackup("This backup is damaged.")
        val key = key(h, password)
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
