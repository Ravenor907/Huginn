package io.github.ravenor907.muninn.forge.core

import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class VerificationException(msg: String) : Exception(msg)

/**
 * Checksum (catches a corrupted download) and cosign signature (catches a
 * tampered one, checked against the publisher key pinned in the repo).
 */
object Verify {
    private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

    fun parseChecksum(text: String, filename: String): String {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (ln in lines) {
            val parts = ln.split(Regex("\\s+"))
            if (parts.size >= 2 && HEX64.matches(parts[0])) {
                val name = parts[1].removePrefix("*").substringAfterLast('/')
                if (name == filename) return parts[0].lowercase()
            }
        }
        if (lines.size == 1 && HEX64.matches(lines[0])) return lines[0].lowercase()
        throw VerificationException("No checksum for $filename in the checksum file")
    }

    fun loadPublicKey(pem: String): PublicKey {
        val b64 = pem.lines().filterNot { it.startsWith("-----") }.joinToString("").trim()
        val der = try {
            Base64.getDecoder().decode(b64)
        } catch (e: IllegalArgumentException) {
            throw VerificationException("Pinned public key is not valid")
        }
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    /** Hash and signature check fed piece by piece, for data that's never all in one file at once. */
    class Running(private val signatureB64: String?, key: PublicKey?) {
        private val md = MessageDigest.getInstance("SHA-256")
        private val sig = if (signatureB64 != null && key != null) {
            Signature.getInstance("SHA256withECDSA").apply { initVerify(key) }
        } else {
            null
        }

        fun update(b: ByteArray, off: Int, len: Int) {
            md.update(b, off, len)
            sig?.update(b, off, len)
        }

        /** The SHA-256 hex, and whether a signature was checked. Throws if the signature is wrong. */
        fun finish(): Pair<String, Boolean> {
            val hex = md.digest().toHex()
            if (sig == null) return hex to false
            val raw = try {
                Base64.getDecoder().decode(signatureB64!!.trim())
            } catch (e: IllegalArgumentException) {
                throw VerificationException("Signature file is not valid base64")
            }
            val ok = try {
                sig.verify(raw)
            } catch (e: java.security.SignatureException) {
                false
            }
            if (!ok) throw VerificationException("Signature does NOT match the pinned key. Refusing this image.")
            return hex to true
        }
    }

    /**
     * Hash the file and, when a signature is given, check it in the same pass
     * (cosign blob signatures are DER ECDSA over SHA-256 of the file).
     * Returns the SHA-256 hex.
     */
    fun hashAndVerify(
        file: File,
        signatureB64: String?,
        key: PublicKey?,
        cancel: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Pair<String, Boolean> {
        val v = Running(signatureB64, key)
        val total = file.length()
        var done = 0L
        val buf = ByteArray(4 shl 20)
        file.inputStream().use { input ->
            while (true) {
                if (cancel()) throw CancelledException()
                val n = input.read(buf)
                if (n < 0) break
                v.update(buf, 0, n)
                done += n
                progress(done, total)
            }
        }
        return v.finish()
    }
}

class CancelledException : Exception("Cancelled")

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
