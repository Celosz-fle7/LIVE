package com.liveStream.app.provider.melolo

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Port Kotlin murni dari ByteDance v8404 Signer (x-gorgon, x-khronos, x-ss-req-ticket, x-ss-stub, x-ladon, x-argus).
 */
object MeloloSigner {
    private val mask64 = BigInteger("FFFFFFFFFFFFFFFF", 16)
    private val maskFF = BigInteger("FF", 16)

    private val sm3Iv = intArrayOf(
        0x7380166f, 0x4914b2b9, 0x172442d7, 0xda8a0600.toInt(),
        0xa96f30bc.toInt(), 0x163138aa, 0xe38dee4d.toInt(), 0xb0fb0e4e.toInt()
    )

    private val argusSignKey = byteArrayOf(
        0xac.toByte(), 0x1a, 0xda.toByte(), 0xae.toByte(), 0x95.toByte(), 0xa7.toByte(), 0xaf.toByte(), 0x94.toByte(),
        0xa5.toByte(), 0x11, 0x4a, 0xb3.toByte(), 0xb3.toByte(), 0xa9.toByte(), 0x7d, 0xd8.toByte(),
        0x00, 0x50, 0xaa.toByte(), 0x0a, 0x39, 0x31, 0x4c, 0x40,
        0x52, 0x8c.toByte(), 0xae.toByte(), 0xc9.toByte(), 0x52, 0x56, 0xc2.toByte(), 0x8c.toByte()
    )

    private val argusSimonKeys = listOf(
        BigInteger("740c7a65a9e078fc", 16),
        BigInteger("03cf3c905915e58c", 16),
        BigInteger("d732f2cfd3510e51", 16),
        BigInteger("04531c328ae84313", 16)
    )

    private val argusAesKey: ByteArray by lazy {
        MessageDigest.getInstance("MD5").digest(argusSignKey.copyOfRange(0, 16))
    }

    private val argusAesIv: ByteArray by lazy {
        MessageDigest.getInstance("MD5").digest(argusSignKey.copyOfRange(16, 32))
    }

    fun canonicalQuery(params: Map<String, String>): String {
        val valid = params.filterValues { it.isNotEmpty() }
        return valid.keys.sorted().joinToString("&") { k ->
            "$k=${URLEncoder.encode(valid[k]!!, "UTF-8")}"
        }
    }

    fun signFromRaw(
        params: Map<String, String>,
        postBody: String = "",
        aid: Int = 645713,
        licenseId: Long = 1611921764L
    ): Map<String, String> {
        val valid = params.filterValues { it.isNotEmpty() }
        val paramStr = canonicalQuery(valid)
        val now = System.currentTimeMillis() / 1000L

        val xSsStub = if (postBody.isNotEmpty()) {
            md5Bytes(postBody.toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it) }
        } else {
            ""
        }

        val gorgon = gorgonV1Sign(paramStr, postBody, now)
        val ladon = ladonEncrypt(now, licenseId, aid)
        val argus = argusGetSign(
            query = paramStr,
            dataStub = xSsStub,
            timestamp = now,
            aid = aid,
            licenseId = licenseId,
            deviceId = valid["device_id"] ?: "7514640337227908615",
            versionName = valid["version_name"] ?: "5.5.5"
        )

        val out = linkedMapOf(
            "x-ss-req-ticket" to gorgon.first,
            "x-khronos" to gorgon.second,
            "x-gorgon" to gorgon.third,
            "x-ladon" to ladon,
            "x-argus" to argus
        )
        if (xSsStub.isNotEmpty()) out["x-ss-stub"] = xSsStub
        return out
    }

    private fun md5Bytes(data: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(data)

    private fun ror64(v: BigInteger, count: Int): BigInteger {
        val c = count % 64
        if (c == 0) return v and mask64
        val low = (v shl (64 - c)) and mask64
        val high = (v shr c) and mask64
        return (high or low) and mask64
    }

    private fun rol64(v: BigInteger, count: Int): BigInteger {
        val c = count % 64
        if (c == 0) return v and mask64
        val high = (v shl c) and mask64
        val low = (v shr (64 - c)) and mask64
        return (high or low) and mask64
    }

    private fun readU64LE(buf: ByteArray, offset: Int): BigInteger {
        var res = BigInteger.ZERO
        for (i in 0 until 8) {
            val b = BigInteger.valueOf((buf[offset + i].toInt() and 0xFF).toLong())
            res = res or (b shl (i * 8))
        }
        return res and mask64
    }

    private fun writeU64LE(buf: ByteArray, offset: Int, value: BigInteger) {
        for (i in 0 until 8) {
            buf[offset + i] = (((value shr (i * 8)) and maskFF).toInt()).toByte()
        }
    }

    private fun simonEncrypt(pt: Pair<BigInteger, BigInteger>, key: List<BigInteger>): Pair<BigInteger, BigInteger> {
        val expanded = Array(72) { BigInteger.ZERO }
        for (i in 0 until 4) expanded[i] = key[i]
        val z0 = BigInteger("3DC94C3A046D678B", 16)
        for (i in 4 until 72) {
            var tmp = ror64(expanded[i - 1], 3)
            tmp = (tmp xor expanded[i - 3]) and mask64
            tmp = (tmp xor ror64(tmp, 1)) and mask64
            val notKey = expanded[i - 4].xor(mask64) and mask64
            val zBit = (z0 shr ((i - 4) % 62)) and BigInteger.ONE
            expanded[i] = (notKey xor tmp xor zBit xor BigInteger.valueOf(3)) and mask64
        }
        var xI = pt.first
        var xI1 = pt.second
        for (i in 0 until 72) {
            val tmp = xI1
            val f = (rol64(xI1, 1) and rol64(xI1, 8)) and mask64
            xI1 = (xI xor f xor rol64(xI1, 2) xor expanded[i]) and mask64
            xI = tmp
        }
        return xI to xI1
    }

    private fun rol32(a: Int, k: Int): Int {
        val s = k % 32
        return if (s == 0) a else (a shl s) or (a ushr (32 - s))
    }

    private fun sm3Hash(msg: ByteArray): ByteArray {
        val bb = ByteArrayOutputStream()
        bb.write(msg)
        bb.write(0x80)
        val reserve = (msg.size % 64) + 1
        val rangeEnd = if (reserve > 56) 120 else 56
        for (i in reserve until rangeEnd) bb.write(0x00)
        val bitLen = msg.size.toLong() * 8L
        for (i in 7 downTo 0) bb.write(((bitLen ushr (i * 8)) and 0xFF).toInt())

        val padded = bb.toByteArray()
        val v = sm3Iv.copyOf()
        val w = IntArray(68)
        val w1 = IntArray(64)

        for (g in 0 until padded.size / 64) {
            val off = g * 64
            for (i in 0 until 16) {
                w[i] = ((padded[off + i * 4].toInt() and 0xFF) shl 24) or
                    ((padded[off + i * 4 + 1].toInt() and 0xFF) shl 16) or
                    ((padded[off + i * 4 + 2].toInt() and 0xFF) shl 8) or
                    (padded[off + i * 4 + 3].toInt() and 0xFF)
            }
            for (j in 16 until 68) {
                val p1In = w[j - 16] xor w[j - 9] xor rol32(w[j - 3], 15)
                val p1 = p1In xor rol32(p1In, 15) xor rol32(p1In, 23)
                w[j] = p1 xor rol32(w[j - 13], 7) xor w[j - 6]
            }
            for (j in 0 until 64) w1[j] = w[j] xor w[j + 4]

            var a = v[0]; var b = v[1]; var c = v[2]; var d = v[3]
            var e = v[4]; var f = v[5]; var gVar = v[6]; var h = v[7]
            for (j in 0 until 64) {
                val tj = if (j < 16) 0x79cc4519 else 0x7a879d8a
                val ss1 = rol32(rol32(a, 12) + e + rol32(tj, j), 7)
                val ss2 = ss1 xor rol32(a, 12)
                val ff = if (j < 16) (a xor b xor c) else ((a and b) or (a and c) or (b and c))
                val gg = if (j < 16) (e xor f xor gVar) else ((e and f) or (e.inv() and gVar))
                val tt1 = ff + d + ss2 + w1[j]
                val tt2 = gg + h + ss1 + w[j]
                d = c; c = rol32(b, 9); b = a; a = tt1
                h = gVar; gVar = rol32(f, 19); f = e; e = tt2 xor rol32(tt2, 9) xor rol32(tt2, 17)
            }
            v[0] = v[0] xor a; v[1] = v[1] xor b; v[2] = v[2] xor c; v[3] = v[3] xor d
            v[4] = v[4] xor e; v[5] = v[5] xor f; v[6] = v[6] xor gVar; v[7] = v[7] xor h
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = ((v[i] ushr 24) and 0xFF).toByte()
            out[i * 4 + 1] = ((v[i] ushr 16) and 0xFF).toByte()
            out[i * 4 + 2] = ((v[i] ushr 8) and 0xFF).toByte()
            out[i * 4 + 3] = (v[i] and 0xFF).toByte()
        }
        return out
    }

    private fun ladonEncrypt(khronos: Long, lcId: Long, aid: Int): String {
        val rand = Random.nextBytes(4)
        val aidBytes = aid.toString().toByteArray(Charsets.UTF_8)
        val keygen = rand + aidBytes
        val md5Hex = md5Bytes(keygen).joinToString("") { "%02x".format(it) }.toByteArray(Charsets.UTF_8)

        val hashTable = ByteArray(288)
        md5Hex.copyInto(hashTable, 0, 0, minOf(32, md5Hex.size))
        val temp = ArrayList<BigInteger>()
        for (i in 0 until 4) temp.add(readU64LE(hashTable, i * 8))

        var b0 = temp.removeAt(0)
        var b8 = temp.removeAt(0)
        for (i in 0 until 0x22) {
            val x9 = b0
            var x8 = ror64(b8, 8)
            x8 = (x8 + x9) and mask64
            x8 = (x8 xor BigInteger.valueOf(i.toLong())) and mask64
            temp.add(x8)
            x8 = (x8 xor ror64(x9, 61)) and mask64
            writeU64LE(hashTable, (i + 1) * 8, x8)
            b0 = x8
            b8 = temp.removeAt(0)
        }

        val data = "$khronos-$lcId-$aid".toByteArray(Charsets.UTF_8)
        val padLen = if (data.size % 16 > 0) 16 - (data.size % 16) else 16
        val padded = ByteArray(data.size + padLen) { idx ->
            if (idx < data.size) data[idx] else padLen.toByte()
        }
        val encrypted = ByteArray(padded.size)
        for (blk in 0 until padded.size / 16) {
            var d0 = readU64LE(padded, blk * 16)
            var d1 = readU64LE(padded, blk * 16 + 8)
            for (i in 0 until 0x22) {
                val h = readU64LE(hashTable, i * 8)
                d1 = (h xor ((d0 + ror64(d1, 8)) and mask64)) and mask64
                d0 = (d1 xor ror64(d0, 0x3D)) and mask64
            }
            writeU64LE(encrypted, blk * 16, d0)
            writeU64LE(encrypted, blk * 16 + 8, d1)
        }
        return Base64.encodeToString(rand + encrypted, Base64.NO_WRAP)
    }

    private fun argusGetSign(
        query: String,
        dataStub: String,
        timestamp: Long,
        aid: Int,
        licenseId: Long,
        deviceId: String,
        versionName: String
    ): String {
        val bodyHash = if (dataStub.isEmpty()) {
            sm3Hash(ByteArray(16)).copyOfRange(0, 6)
        } else {
            val hex = ByteArray(dataStub.length / 2) { i ->
                dataStub.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            sm3Hash(hex).copyOfRange(0, 6)
        }
        val queryHash = if (query.isEmpty()) {
            sm3Hash(ByteArray(16)).copyOfRange(0, 6)
        } else {
            sm3Hash(query.toByteArray(Charsets.UTF_8)).copyOfRange(0, 6)
        }

        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val secDev = "AadCFwpTyztA5j9L" + (1..9).map { chars[Random.nextInt(chars.length)] }.joinToString("")

        val w = ProtoWriter().apply {
            writeInt(1, (0x20200929L shl 1))
            writeInt(2, 2L)
            writeInt(3, Random.nextInt(0x7FFFFFFF).toLong())
            writeString(4, aid.toString())
            writeString(5, deviceId)
            writeString(6, licenseId.toString())
            writeString(7, versionName)
            writeString(8, "v04.04.05-ov-android")
            writeInt(9, 134744640L)
            writeBytes(10, ByteArray(8))
            writeInt(11, 0L)
            writeInt(12, timestamp shl 1)
            writeBytes(13, bodyHash)
            writeBytes(14, queryHash)

            val w15 = ProtoWriter().apply {
                writeInt(1, 1L)
                writeInt(2, 1L)
                writeInt(3, 1L)
                writeInt(7, 3348294860L)
            }
            writeBytes(15, w15.toBytes())
            writeString(16, secDev)
            writeString(20, "none")
            writeInt(21, 738L)

            val w23 = ProtoWriter().apply {
                writeString(1, "NX551J")
                writeInt(2, 8196L)
                writeInt(4, 2162219008L)
            }
            writeBytes(23, w23.toBytes())
            writeInt(25, 2L)
        }

        val pb = w.toBytes()
        val padLen = if (pb.size % 16 > 0) 16 - (pb.size % 16) else 16
        val padded = ByteArray(pb.size + padLen) { idx ->
            if (idx < pb.size) pb[idx] else padLen.toByte()
        }
        val encPb = ByteArray(padded.size)
        for (i in 0 until padded.size / 16) {
            val ct = simonEncrypt(
                readU64LE(padded, i * 16) to readU64LE(padded, i * 16 + 8),
                argusSimonKeys
            )
            writeU64LE(encPb, i * 16, ct.first)
            writeU64LE(encPb, i * 16 + 8, ct.second)
        }

        val prefix8 = byteArrayOf(
            0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte(),
            0xf2.toByte(), 0xf7.toByte(), 0xfc.toByte(), 0xff.toByte()
        )
        val toXor = prefix8 + encPb
        val xorArr = toXor.copyOfRange(0, 8)
        for (i in 8 until toXor.size) {
            toXor[i] = (toXor[i].toInt() xor xorArr[i % 8].toInt()).toByte()
        }
        val bBufferInner = toXor.reversedArray()
        val header9 = byteArrayOf(
            0xa6.toByte(), 0x6e, 0xad.toByte(), 0x9f.toByte(), 0x77, 0x01, 0xd0.toByte(), 0x0c, 0x18
        )
        val trailer2 = byteArrayOf(0x61, 0x6f)
        val bBuffer = header9 + bBufferInner + trailer2

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(argusAesKey, "AES"), IvParameterSpec(argusAesIv))
        val aesCipher = cipher.doFinal(bBuffer)

        val finalOut = byteArrayOf(0xf2.toByte(), 0x81.toByte()) + aesCipher
        return Base64.encodeToString(finalOut, Base64.NO_WRAP)
    }

    private fun gorgonV1Sign(params: String, payload: String, unix: Long): Triple<String, String, String> {
        val gorgon = ByteArrayOutputStream()
        gorgon.write(md5Bytes(params.toByteArray(Charsets.UTF_8)), 0, 4)
        if (payload.isNotEmpty()) {
            gorgon.write(md5Bytes(payload.toByteArray(Charsets.UTF_8)), 0, 4)
        } else {
            gorgon.write(byteArrayOf(0, 0, 0, 0))
        }
        gorgon.write(byteArrayOf(0, 0, 0, 0))
        gorgon.write(byteArrayOf(0x01, 0x01, 0x02, 0x04))
        val khHex = unix.toString(16).padStart(8, '0')
        for (i in 0 until 4) {
            gorgon.write(khHex.substring(i * 2, i * 2 + 2).toInt(16))
        }
        val hexCe7 = "%02x".format(Random.nextInt(256) and 0xF0)
        val hexCe3 = "%02x".format(Random.nextInt(256))
        val bodyHex = gorgon.toByteArray().joinToString("") { "%02x".format(it) }
        val gorgonStr = "8404$hexCe7${hexCe3}0000$bodyHex"
        return Triple((unix * 1000L).toString(), unix.toString(), gorgonStr)
    }

    private class ProtoWriter {
        private val bb = ByteArrayOutputStream()

        fun writeVarint(value: Long) {
            var v = value and 0xFFFFFFFFL
            while (v > 0x7FL) {
                bb.write(((v and 0x7FL) or 0x80L).toInt())
                v = v ushr 7
            }
            bb.write((v and 0x7FL).toInt())
        }

        fun writeInt(fieldNumber: Int, value: Long) {
            writeVarint(((fieldNumber shl 3) or 0).toLong())
            writeVarint(value)
        }

        fun writeString(fieldNumber: Int, str: String) {
            writeBytes(fieldNumber, str.toByteArray(Charsets.UTF_8))
        }

        fun writeBytes(fieldNumber: Int, bytes: ByteArray) {
            writeVarint(((fieldNumber shl 3) or 2).toLong())
            writeVarint(bytes.size.toLong())
            bb.write(bytes)
        }

        fun toBytes(): ByteArray = bb.toByteArray()
    }
}
