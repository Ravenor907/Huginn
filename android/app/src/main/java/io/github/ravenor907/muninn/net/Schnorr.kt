package io.github.ravenor907.muninn.net

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 Schnorr signatures on secp256k1: what Nostr relays require on every event. Written
 * straight from the BIP's reference code; the tests check it against the BIP's official values.
 * Muninn only signs a handful of small events, so plain BigInteger math is fast enough.
 */
object Schnorr {
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    val N: BigInteger = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val G = Point(
        BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16),
        BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16),
    )
    private val random = SecureRandom()
    private val THREE = BigInteger.valueOf(3)
    private val SEVEN = BigInteger.valueOf(7)
    private val TWO = BigInteger.valueOf(2)

    private data class Point(val x: BigInteger, val y: BigInteger)

    private fun add(a: Point?, b: Point?): Point? {
        if (a == null) return b
        if (b == null) return a
        if (a.x == b.x && a.y != b.y) return null
        val lam = if (a == b) THREE * a.x * a.x * (TWO * a.y).modInverse(P) % P
            else (b.y - a.y) * (b.x - a.x).modInverse(P) % P
        val x3 = (lam * lam - a.x - b.x).mod(P)
        return Point(x3, (lam * (a.x - x3) - a.y).mod(P))
    }

    private fun mul(point: Point, k: BigInteger): Point? {
        var r: Point? = null
        var p: Point? = point
        for (i in 0 until 256) {
            if (k.testBit(i)) r = add(r, p)
            p = add(p, p)
        }
        return r
    }

    private fun bytes32(x: BigInteger): ByteArray {
        val b = x.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun int(b: ByteArray) = BigInteger(1, b)

    private fun taggedHash(tag: String, msg: ByteArray): ByteArray {
        val t = MessageDigest.getInstance("SHA-256").digest(tag.toByteArray())
        return MessageDigest.getInstance("SHA-256").digest(t + t + msg)
    }

    private fun liftX(x: BigInteger): Point? {
        if (x >= P) return null
        val ySq = (x.modPow(THREE, P) + SEVEN).mod(P)
        val y = ySq.modPow((P + BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(TWO, P) != ySq) return null
        return Point(x, if (!y.testBit(0)) y else P - y)
    }

    fun newSecret(): ByteArray {
        while (true) {
            val b = ByteArray(32).also { random.nextBytes(it) }
            val d = int(b)
            if (d.signum() > 0 && d < N) return b
        }
    }

    /** x-only public key, as Nostr uses it. */
    fun publicKey(secret: ByteArray): ByteArray = bytes32(mul(G, int(secret))!!.x)

    fun sign(msg: ByteArray, secret: ByteArray, aux: ByteArray = ByteArray(32).also { random.nextBytes(it) }): ByteArray {
        val d0 = int(secret)
        require(d0.signum() > 0 && d0 < N) { "bad secret key" }
        val pt = mul(G, d0)!!
        val d = if (!pt.y.testBit(0)) d0 else N - d0
        val t = bytes32(d).zip(taggedHash("BIP0340/aux", aux)) { a, b -> (a.toInt() xor b.toInt()).toByte() }.toByteArray()
        val k0 = int(taggedHash("BIP0340/nonce", t + bytes32(pt.x) + msg)).mod(N)
        require(k0.signum() != 0)
        val r = mul(G, k0)!!
        val k = if (!r.y.testBit(0)) k0 else N - k0
        val e = int(taggedHash("BIP0340/challenge", bytes32(r.x) + bytes32(pt.x) + msg)).mod(N)
        return bytes32(r.x) + bytes32((k + e * d).mod(N))
    }

    fun verify(msg: ByteArray, pubkey: ByteArray, sig: ByteArray): Boolean {
        if (sig.size != 64 || pubkey.size != 32) return false
        val pt = liftX(int(pubkey)) ?: return false
        val r = int(sig.copyOfRange(0, 32))
        val s = int(sig.copyOfRange(32, 64))
        if (r >= P || s >= N) return false
        val e = int(taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32) + pubkey + msg)).mod(N)
        val rr = add(mul(G, s), mul(pt, N - e)) ?: return false
        return !rr.y.testBit(0) && rr.x == r
    }

    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
