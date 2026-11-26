package com.liveStream.app.provider.dramabox

import android.util.Base64
import java.math.BigInteger
import java.security.MessageDigest

/**
 * Request signing resmi DramaBox: `sn` (SHA256WithRSA PKCS#1 v1.5) & `st` (DJB2 10-table substitution).
 */
object DramaBoxSigner {
    private val rsaModN = BigInteger(
        "bd438639417e63d3c1eb9db6378ab7ca76412594053638e4670239570f20b3726d99e9946" +
            "b1afb5365400c7a57d4e4f592e971849b777890910a6d78b80ab97ca3613f1376093e4d6" +
            "5403d5dd094ddaef6657844cdd55887645490c12dcbf785a88741374cf79b68462af3c78" +
            "4c6f2aed6d0dd8d2a058c16e031ecf24a76ace4d736c45dbc5e13ee0bc9b5e6ef9d79cb6" +
            "cac997e00b19c4c35f72efdc5511bb057f74ded630492072c78829531d271cd40743de4a" +
            "a08d7ed7f199d055fd4e325c54713e19d21525c2ae48fab898e2724898ab5a50d5a65fad" +
            "07b578b0f7301da12aaedcae131c0379298204259e6cb5c11478040b6d9925387cf6ffc8" +
            "5f03de1",
        16
    )

    private val rsaExpD = BigInteger(
        "976bbbaa7944b67787f18b176aee77c6eabf8a62179d5efd1590c8f167300181ae4072898" +
            "4fb6c5237727bc93216292c746282a8d67d4a1f577b614430f29b9e2554bb53459f75921" +
            "44cc60c15c1d46e93c170ec93457a73b0089c5b81d96aea2b8032e41c970965eea594ae6" +
            "f0380cbf31fddf17fca3a5f5ef5729e12874a20d516bb13d199dfb7730655f668904af15" +
            "05f72d667b6e0cfc79d9eef59da7ea7db8d7c59b84b69bbdda825de80f6659b7740594e4" +
            "ef66475260dc29caa37dc107d7d4963a984c245624c54e45468204af7ff38acc933b832d" +
            "df7666c042cead8f4f765c8003279ded9a07fa1953afb734db94113af6f62e6aa0462513" +
            "e7ab4c1",
        16
    )

    private val mask64 = BigInteger("FFFFFFFFFFFFFFFF", 16)
    private val mask8 = BigInteger.valueOf(0xffL)

    private val cleanTables = arrayOf(
        intArrayOf(107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 82, 54, 115, 84, 55, 117, 86, 56, 119, 88, 57, 121, 90, 48, 97, 66, 49, 99, 68, 50, 101, 70, 45, 71, 72, 73, 74, 95, 75, 77, 79, 81, 83, 85, 87, 89, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 65, 67, 69, 103, 105, 64, 91, 92, 93, 94, 58, 59, 60, 61, 62, 63),
        intArrayOf(55, 117, 86, 56, 119, 88, 57, 121, 90, 48, 97, 66, 49, 99, 68, 50, 101, 70, 45, 71, 72, 73, 74, 95, 75, 77, 79, 81, 83, 85, 87, 89, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 65, 67, 69, 103, 105, 107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 82, 54, 115, 84, 94, 58, 59, 60, 61, 62, 63, 64, 91, 92, 93),
        intArrayOf(98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 65, 67, 69, 103, 105, 107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 82, 54, 115, 84, 55, 117, 86, 56, 119, 88, 57, 121, 90, 48, 97, 66, 49, 99, 68, 50, 101, 70, 45, 71, 72, 73, 74, 95, 75, 77, 79, 81, 83, 85, 87, 89, 60, 61, 62, 63, 64, 91, 92, 93, 94, 58, 59),
        intArrayOf(49, 99, 68, 50, 101, 70, 45, 71, 72, 73, 74, 95, 75, 77, 79, 81, 83, 85, 87, 89, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 65, 67, 69, 103, 105, 107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 82, 54, 115, 84, 55, 117, 86, 56, 119, 88, 57, 121, 90, 48, 97, 66, 59, 58, 94, 93, 92, 91, 64, 63, 62, 61, 60),
        intArrayOf(77, 79, 81, 83, 85, 87, 89, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 65, 67, 69, 103, 105, 107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 82, 54, 115, 84, 55, 117, 86, 56, 119, 88, 57, 121, 90, 48, 97, 66, 49, 99, 68, 50, 101, 70, 45, 71, 72, 73, 74, 95, 75, 91, 92, 93, 94, 58, 59, 60, 61, 62, 63, 64),
        intArrayOf(82, 54, 115, 84, 55, 117, 86, 56, 119, 88, 57, 121, 90, 48, 97, 66, 49, 99, 68, 50, 101, 70, 45, 71, 72, 73, 74, 95, 75, 77, 79, 81, 83, 85, 87, 89, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 65, 67, 69, 103, 105, 107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 63, 64, 91, 92, 93, 94, 58, 59, 60, 61, 62),
        intArrayOf(101, 70, 45, 71, 72, 73, 74, 95, 75, 77, 79, 81, 83, 85, 87, 89, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 65, 67, 69, 103, 105, 107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 82, 54, 115, 84, 55, 117, 86, 56, 119, 88, 57, 121, 90, 48, 97, 66, 49, 99, 68, 50, 61, 62, 63, 64, 91, 92, 93, 94, 58, 59, 60),
        intArrayOf(120, 122, 65, 67, 69, 103, 105, 107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 82, 54, 115, 84, 55, 117, 86, 56, 119, 88, 57, 121, 90, 48, 97, 66, 49, 99, 68, 50, 101, 70, 45, 71, 72, 73, 74, 95, 75, 77, 79, 81, 83, 85, 87, 89, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 93, 92, 91, 64, 63, 62, 61, 60, 59, 58, 94),
        intArrayOf(121, 90, 48, 97, 66, 49, 99, 68, 50, 101, 70, 45, 71, 72, 73, 74, 95, 75, 77, 79, 81, 83, 85, 87, 89, 98, 100, 102, 104, 106, 108, 110, 112, 114, 116, 118, 120, 122, 65, 67, 69, 103, 105, 107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 82, 54, 115, 84, 55, 117, 86, 56, 119, 88, 57, 62, 63, 64, 91, 92, 93, 94, 58, 59, 60, 61),
        intArrayOf(108, 110, 112, 114, 116, 118, 120, 122, 65, 67, 69, 103, 105, 107, 76, 51, 109, 78, 52, 111, 80, 53, 113, 82, 54, 115, 84, 55, 117, 86, 56, 119, 88, 57, 121, 90, 48, 97, 66, 49, 99, 68, 50, 101, 70, 45, 71, 72, 73, 74, 95, 75, 77, 79, 81, 83, 85, 87, 89, 98, 100, 102, 104, 106, 92, 94, 93, 91, 58, 59, 64, 63, 62, 61, 60)
    )

    private const val ST_PREFIX = "cK4n10B_0tTQBrxF"
    private const val ST_FIXED =
        "MySuperSecretSalt_DoNotLeak|com.storymatrix.drama|003cc4921e198429f7dd7066244bfe5efccdfeea01adf25e91c96d64478c00a1|"

    private val sha256DigestInfo = byteArrayOf(
        0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, 0x86.toByte(), 0x48, 0x01,
        0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00, 0x04, 0x20
    )

    fun signSn(content: String): String {
        val k = 256
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        val digestInfo = ByteArray(19 + 32)
        sha256DigestInfo.copyInto(digestInfo, 0)
        digest.copyInto(digestInfo, 19)

        val padLen = k - 3 - digestInfo.size
        val block = ByteArray(k)
        block[0] = 0x00
        block[1] = 0x01
        for (i in 0 until padLen) block[2 + i] = 0xff.toByte()
        block[2 + padLen] = 0x00
        digestInfo.copyInto(block, 3 + padLen)

        val m = BigInteger(1, block)
        val s = m.modPow(rsaExpD, rsaModN)
        val raw = s.toByteArray()
        val out = ByteArray(k)
        if (raw.size >= k) {
            raw.copyInto(out, 0, raw.size - k, raw.size)
        } else {
            raw.copyInto(out, k - raw.size)
        }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun signSt(content: String): String {
        var h = BigInteger.valueOf(5381L)
        val t0 = cleanTables[0]
        for (ch in content) {
            var c = ch.code
            val diff = c - 0x30
            if (diff in 0..0x4a) c = t0[diff]
            h = h.shiftLeft(5).add(h).add(BigInteger.valueOf(c.toLong())).and(mask64)
        }

        val tblIdx = h.and(mask8).toInt() % 10
        val tbl = cleanTables[tblIdx]
        for (ch in ST_FIXED) {
            var c = ch.code
            val diff = c - 0x30
            if (diff in 0..0x4a) c = tbl[diff]
            h = h.shiftLeft(5).add(h).add(BigInteger.valueOf(c.toLong())).and(mask64)
        }

        val raw6 = intArrayOf(
            0x00,
            0x01,
            h.shiftRight(56).and(mask8).toInt(),
            h.shiftRight(48).and(mask8).toInt(),
            h.shiftRight(40).and(mask8).toInt(),
            h.shiftRight(32).and(mask8).toInt()
        )
        val sb = StringBuilder()
        var i = 0
        while (i < 6) {
            val chunk = (raw6[i] shl 16) or (raw6[i + 1] shl 8) or raw6[i + 2]
            sb.append(tbl[(chunk ushr 18) and 0x3f].toChar())
            sb.append(tbl[(chunk ushr 12) and 0x3f].toChar())
            sb.append(tbl[(chunk ushr 6) and 0x3f].toChar())
            sb.append(tbl[chunk and 0x3f].toChar())
            i += 3
        }
        return ST_PREFIX + sb.toString()
    }
}
