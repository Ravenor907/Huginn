package io.github.ravenor907.muninn.net.tor

import org.json.JSONObject
import java.security.MessageDigest

/**
 * This phone's onion service key, as Tor hands it out ("ED25519-V3:" + base64 of the 64-byte
 * expanded secret key), and the onion address it belongs to. Kept sealed in the Vault, so the
 * address stays the same across restarts and moves with a backup.
 */
data class OnionKey(val key: String, val id: String) {
    fun encode(): ByteArray = JSONObject().put("v", 1).put("key", key).put("id", id).toString().toByteArray()

    companion object {
        const val PREFIX = "ED25519-V3:"

        /** Null for anything that isn't a whole, well-formed record. */
        fun decode(bytes: ByteArray?): OnionKey? = try {
            val o = JSONObject(String(bytes ?: return null))
            val k = OnionKey(o.getString("key"), o.getString("id"))
            if (o.optInt("v") == 1 && validKey(k.key) && OnionAddress.valid(k.id)) k else null
        } catch (e: Exception) { null }

        fun validKey(key: String): Boolean = key.startsWith(PREFIX) && try {
            java.util.Base64.getDecoder().decode(key.removePrefix(PREFIX)).size == 64
        } catch (e: Exception) { false }
    }
}

/**
 * Version 3 onion addresses (rend-spec-v3): base32 of pubkey (32) + checksum (2) + version (3),
 * where the checksum is the start of SHA3-256(".onion checksum" + pubkey + version). Written
 * here without ".onion", lowercase, 56 letters.
 */
object OnionAddress {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"
    private val shape = Regex("^[a-z2-7]{56}$")

    /** "ABC...xyz.onion" or "abc...xyz" to "abc...xyz", or null if it isn't a v3 address. */
    fun normalize(s: String): String? = s.trim().lowercase().removeSuffix(".onion").takeIf { valid(it) }

    fun valid(id: String): Boolean {
        if (!shape.matches(id)) return false
        val raw = base32Decode(id) ?: return false
        if (raw.size != 35 || raw[34] != 3.toByte()) return false
        val sum = checksum(raw.copyOfRange(0, 32)) ?: return true   // no SHA3 on this phone: the shape has to do
        return raw[32] == sum[0] && raw[33] == sum[1]
    }

    fun fromPublicKey(pub: ByteArray): String {
        require(pub.size == 32)
        val sum = checksum(pub) ?: throw IllegalStateException("SHA3-256 is missing")
        return base32Encode(pub + sum.copyOfRange(0, 2) + byteArrayOf(3))
    }

    /** The two checksum bytes' source, or null where SHA3-256 isn't available (older Android). */
    private fun checksum(pub: ByteArray): ByteArray? = try {
        MessageDigest.getInstance("SHA3-256").digest(".onion checksum".toByteArray() + pub + byteArrayOf(3))
    } catch (e: Exception) { null }

    fun base32Encode(b: ByteArray): String {
        val out = StringBuilder()
        var buf = 0; var bits = 0
        for (x in b) {
            buf = (buf shl 8) or (x.toInt() and 0xff); bits += 8
            while (bits >= 5) { out.append(ALPHABET[(buf shr (bits - 5)) and 31]); bits -= 5 }
        }
        if (bits > 0) out.append(ALPHABET[(buf shl (5 - bits)) and 31])
        return out.toString()
    }

    fun base32Decode(s: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        var buf = 0; var bits = 0
        for (c in s) {
            val v = ALPHABET.indexOf(c)
            if (v < 0) return null
            buf = (buf shl 5) or v; bits += 5
            if (bits >= 8) { out.write((buf shr (bits - 8)) and 0xff); bits -= 8 }
        }
        return out.toByteArray()
    }
}
