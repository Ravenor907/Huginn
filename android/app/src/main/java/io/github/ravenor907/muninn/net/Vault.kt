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
 *  - with a password set, by the password instead (PBKDF2, 310,000 rounds): without the password
 *    nothing is readable, not even by Muninn.
 */
class Vault(private val context: Context) {
    private val keyFile = File(context.filesDir, "vault.key")
    @Volatile private var dataKey: ByteArray? = null
    private val _locked = MutableStateFlow(true)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    val hasPassword: Boolean get() = read()?.optString("mode") == "password"

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
        _locked.value = false
        return true
    }

    fun unlock(password: String): Boolean {
        val o = read() ?: return openDefault()
        if (o.optString("mode") != "password") return openDefault()
        val kek = Crypto.derive(password, Crypto.b64d(o.getString("salt")), o.optInt("rounds", ROUNDS))
        dataKey = Crypto.open(kek, Crypto.b64d(o.getString("wrapped"))) ?: return false
        _locked.value = false
        return true
    }

    fun lock() {
        if (!hasPassword) return   // without a password there's nothing to lock with
        dataKey?.fill(0)
        dataKey = null
        _locked.value = true
    }

    /** Sets, changes or (with null) removes the password. Must be unlocked. */
    fun setPassword(password: String?) {
        val k = dataKey ?: return
        store(k, password)
    }

    private fun store(k: ByteArray, password: String?) {
        val o = JSONObject()
        if (password.isNullOrEmpty()) {
            o.put("mode", "keystore").put("wrapped", Crypto.b64(Crypto.seal(systemKey(), k)))
        } else {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            o.put("mode", "password").put("salt", Crypto.b64(salt)).put("rounds", ROUNDS)
                .put("wrapped", Crypto.b64(Crypto.seal(Crypto.derive(password, salt, ROUNDS), k)))
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

    /** Null if missing, locked or damaged. Files from before encryption are read once and re-saved encrypted. */
    fun read(file: File): String? {
        if (!file.exists()) return null
        val k = dataKey ?: return null
        val b = file.readBytes()
        if (!b.take(Crypto.MAGIC.size).toByteArray().contentEquals(Crypto.MAGIC)) {
            val plain = String(b)
            write(file, plain)
            return plain
        }
        return Crypto.open(k, b.copyOfRange(Crypto.MAGIC.size, b.size))?.let { String(it) }
    }

    /** Erases everything Muninn keeps: chats, contacts, keys, settings. */
    fun eraseAll() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        context.getSharedPreferences("muninn", Context.MODE_PRIVATE).edit().clear().commit()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
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

    companion object {
        private const val ALIAS = "muninn-vault"
        private const val ROUNDS = 310_000
    }
}
