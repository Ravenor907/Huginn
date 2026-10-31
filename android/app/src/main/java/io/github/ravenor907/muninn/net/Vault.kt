package io.github.ravenor907.muninn.net

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Everything Muninn keeps (chats, contacts, keys) is encrypted on the phone with one random data
 * key (AES-256-GCM). That data key is itself locked:
 *  - by default, with a key in Android's hardware key store, so only Muninn on this phone can open it;
 *  - with a password set, by the password (PBKDF2, 310,000 rounds) and then by the hardware key too:
 *    without the password nothing is readable, not even by Muninn, and copied files can't be guessed
 *    at on another computer, only on this phone (with its waits after wrong tries).
 */
class Vault(private val context: Context) {
    private val keyFile = File(context.filesDir, "vault.key")
    @Volatile private var dataKey: ByteArray? = null
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    val hasPassword: Boolean get() = read()?.optString("mode") == "password"
    /** The lock is a drawn pattern rather than a typed password. */
    val usesPattern: Boolean get() = read()?.optString("kind") == "pattern"

    /** Opens without a password when none is set. Returns false if a password is needed. */
    fun openDefault(): Boolean {
        if (dataKey != null) return true
        val o = read()
        if (o == null) {
            val k = ByteArray(32).also { SecureRandom().nextBytes(it) }
            store(k, null)
            dataKey = k
        } else if (o.optString("mode") == "keystore") {
            dataKey = Crypto.open(systemKey(), Crypto.b64d(o.getString("wrapped"))) ?: return false
        } else {
            return false
        }
        applyRestore()
        _locked.value = false
        return true
    }

    fun unlock(password: String): Boolean {
        val o = read() ?: return openDefault()
        if (o.optString("mode") != "password") return openDefault()
        val kek = Crypto.derive(password, Crypto.b64d(o.getString("salt")), o.optInt("rounds", ROUNDS))
        val wasClosed = dataKey == null
        val hw = o.optInt("hw", 0) == 1
        val wrapped = Crypto.b64d(o.getString("wrapped"))
        val inner = if (hw) Crypto.open(systemKey(), wrapped) ?: return false else wrapped
        val k = Crypto.open(kek, inner) ?: return false
        dataKey = k
        // Locks from before 0.95 were only the password: wrap with the hardware key too, now that it's known.
        if (!hw) try { store(k, password, o.optString("kind") == "pattern") } catch (_: Exception) {}
        weak = !LockRules.strong(password)
        if (wasClosed) applyRestore()   // only on a fresh open, before chats load
        _locked.value = false
        return true
    }

    /** The lock just opened is shorter than new locks may be (from before 0.95). Only known after an unlock. */
    @Volatile var weak = false
        private set

    fun lock() {
        if (!hasPassword) return   // without a password there's nothing to lock with
        dataKey?.fill(0)
        dataKey = null
        _locked.value = true
    }

    /** Sets, changes or (with null) removes the password. Must be unlocked. */
    fun setPassword(password: String?, pattern: Boolean = false) {
        val k = dataKey ?: return
        store(k, password, pattern)
        if (!password.isNullOrEmpty()) weak = !LockRules.strong(password)
    }

    private fun store(k: ByteArray, password: String?, pattern: Boolean = false) {
        val o = JSONObject()
        if (password.isNullOrEmpty()) {
            o.put("mode", "keystore").put("wrapped", Crypto.b64(Crypto.seal(systemKey(), k)))
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            // The password's lock, then the hardware key's around it ("hw": 1), so guessing has to happen on this phone.
            o.put("mode", "password").put("kind", if (pattern) "pattern" else "text").put("salt", Crypto.b64(salt)).put("rounds", ROUNDS)
                .put("hw", 1).put("wrapped", Crypto.b64(Crypto.seal(systemKey(), Crypto.seal(Crypto.derive(password, salt, ROUNDS), k))))
        }
        val tmp = File(keyFile.path + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(keyFile)
    }

    private fun read(): JSONObject? = try { JSONObject(keyFile.readText()) } catch (e: Exception) { null }

    // ------------------------------------------------------------------ files

    fun write(file: File, text: String) {
        val k = dataKey ?: throw IllegalStateException("Muninn is locked")
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(Crypto.MAGIC + Crypto.seal(k, text.toByteArray()))
        tmp.renameTo(file)
    }

    fun writeBytes(file: File, data: ByteArray) {
        val k = dataKey ?: throw IllegalStateException("Muninn is locked")
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(Crypto.MAGIC + Crypto.seal(k, data))
        tmp.renameTo(file)
    }

    fun readBytes(file: File): ByteArray? {
        if (!file.exists()) return null
        val k = dataKey ?: return null
        val b = file.readBytes()
        if (b.size < Crypto.MAGIC.size || !b.copyOfRange(0, Crypto.MAGIC.size).contentEquals(Crypto.MAGIC)) return null
        return Crypto.open(k, b.copyOfRange(Crypto.MAGIC.size, b.size))
    }

    /** Null if missing, locked or damaged. A file that isn't sealed is never taken (another app could have put it there). */
    fun read(file: File): String? {
        if (!file.exists()) return null
        val k = dataKey ?: return null
        val b = file.readBytes()
        if (b.size < Crypto.MAGIC.size || !b.copyOfRange(0, Crypto.MAGIC.size).contentEquals(Crypto.MAGIC)) return null
        return Crypto.open(k, b.copyOfRange(Crypto.MAGIC.size, b.size))?.let { String(it) }
    }

    // ------------------------------------------------------------------ backups

    /** True if [file] is one of the vault's encrypted files. */
    fun isSealed(file: File): Boolean = try {
        file.isFile && file.length() >= Crypto.MAGIC.size &&
            file.inputStream().use { s -> ByteArray(Crypto.MAGIC.size).also { s.read(it) } }.contentEquals(Crypto.MAGIC)
    } catch (e: Exception) { false }

    /** Every encrypted file Muninn keeps (chats, contacts, keys, photos, voice, sessions), by name relative to its folder. */
    fun sealedFiles(): List<Pair<String, File>> {
        val root = context.filesDir
        return root.walkTopDown().onEnter { it == root || it.name != STAGE && !(it.parentFile == root && it.name == "tls") }
            .filter { it.isFile && it.name != keyFile.name && !it.name.endsWith(".tmp") && isSealed(it) }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }.toList()
    }

    /**
     * Gets a restore ready: each file is encrypted with this phone's key into a waiting folder. Nothing
     * running is touched; the swap happens on the next open (see [applyRestore]), before chats load,
     * so nothing still in memory can write over it. Must be unlocked.
     */
    fun stageRestore(files: List<Pair<String, ByteArray>>) {
        val k = dataKey ?: throw IllegalStateException("Muninn is locked")
        val dir = File(context.filesDir, STAGE)
        dir.deleteRecursively(); dir.mkdirs()
        val names = ArrayList<String>()
        for ((name, data) in files) {
            if (!BackupFormat.safeName(name) || name == keyFile.name || name == DONE || name.substringBefore('/') in setOf(STAGE, "tls")) continue
            val f = File(dir, name)
            f.parentFile?.mkdirs()
            f.writeBytes(Crypto.MAGIC + Crypto.seal(k, data))
            names += name
        }
        // Written last: without it the folder is an unfinished restore and is thrown away.
        File(dir, DONE).writeText(names.joinToString("\n"))
    }

    /** Swaps in a staged restore. Safe to repeat if it's interrupted. */
    private fun applyRestore() {
        val dir = File(context.filesDir, STAGE)
        if (!dir.exists()) return
        val done = File(dir, DONE)
        if (!done.exists()) { dir.deleteRecursively(); return }
        val names = done.readLines().filter { it.isNotEmpty() }.toSet()
        try {
            sealedFiles().forEach { (name, f) -> if (name !in names) f.delete() }
            for (name in names) {
                val src = File(dir, name)
                if (!src.exists()) continue
                val dst = File(context.filesDir, name)
                dst.parentFile?.mkdirs()
                if (!src.renameTo(dst)) { dst.delete(); src.renameTo(dst) }
            }
            dir.deleteRecursively()
        } catch (_: Exception) {}
    }

    /**
     * Erases everything Muninn keeps: chats, contacts, keys, settings, files waiting in the cache (shared in,
     * camera shots, parts of a move), every settings file (moving ones too) and the background work list.
     */
    fun eraseAll() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.forEach { f ->
            if (!f.name.endsWith(".xml")) return@forEach
            try { context.getSharedPreferences(f.name.removeSuffix(".xml"), Context.MODE_PRIVATE).edit().clear().commit() } catch (_: Exception) {}
            try { context.deleteSharedPreferences(f.name.removeSuffix(".xml")) } catch (_: Exception) {}
            f.delete()
        }
        // WorkManager's list of background jobs (the inbox check): it holds no messages, but it shows Muninn was used.
        for (name in listOf("androidx.work.workdb", "androidx.work.workdb-wal", "androidx.work.workdb-shm")) context.getDatabasePath(name).delete()
        context.getDatabasePath("androidx.work.workdb").parentFile?.listFiles()?.forEach { if (it.name.startsWith("androidx.work")) it.delete() }
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
        dataKey?.fill(0)
        dataKey = null
    }

    private fun systemKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    /** The math, separate so it can be tested off the phone. */
    object Crypto {
        val MAGIC = "MUNV1".toByteArray()
        private val random = SecureRandom()

        fun seal(key: SecretKey, plain: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key)
            return c.iv + c.doFinal(plain)
        }
        fun seal(key: ByteArray, plain: ByteArray): ByteArray {
            val iv = ByteArray(12).also { random.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            return iv + c.doFinal(plain)
        }
        fun open(key: SecretKey, data: ByteArray): ByteArray? = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 0, 12))
            c.doFinal(data, 12, data.size - 12)
        } catch (e: Exception) { null }
        fun open(key: ByteArray, data: ByteArray): ByteArray? = open(SecretKeySpec(key, "AES"), data)
        fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(password.toCharArray(), salt, rounds, 256)).encoded
        fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
        fun b64d(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)
    }

    /** How long a new lock must be. Locks from before 0.95 may be shorter: they keep working, with one suggestion. */
    object LockRules {
        const val MIN = 6
        /** "pattern:0,3,6,..." counts dots; a password counts characters. */
        fun strong(secret: String): Boolean =
            if (secret.startsWith("pattern:")) secret.removePrefix("pattern:").split(',').count { it.isNotEmpty() } >= MIN
            else secret.length >= MIN
    }

    companion object {
        private const val ALIAS = "muninn-vault"
        private const val ROUNDS = 310_000
        /** Where a restore waits for the next open. */
        const val STAGE = "restore-pending"
        private const val DONE = ".done"
    }
}
