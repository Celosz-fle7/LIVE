package com.liveStream.app.provider.dramabox

import android.util.Base64
import com.liveStream.app.core.InMemoryMediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dekriptor in-memory Aliyun License private-encryption (`.encrypt.mp4`) untuk DramaBox.
 * Tanpa ServerSocket — output disimpan ke InMemoryMediaStore (`mem://`).
 */
object AliyunEcbDecryptor {
    private const val LICENSE_KEY = "8PfuCPCwtaTqTB00lca1b30cbaa074865b30efce2c7108498"

    fun preparePlayableUrl(
        cacheKey: String,
        rawUrl: String,
        client: OkHttpClient
    ): String {
        if (!rawUrl.contains(".encrypt")) return rawUrl
        val memKey = "$cacheKey.mp4"
        if (InMemoryMediaStore.get(memKey) != null) return "mem://$memKey"

        val req = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", "DramaBox/7.0.0 (Android)")
            .header("Accept", "*/*")
            .build()
        val rawBytes = runCatching {
            client.newCall(req).execute().use { it.body?.bytes() }
        }.getOrNull() ?: return rawUrl
        if (rawBytes.size < 100) return rawUrl

        val clean = runCatching { decryptMp4(rawBytes) }.getOrNull() ?: return rawUrl
        if (clean.isEmpty()) return rawUrl
        InMemoryMediaStore.put(memKey, clean)
        return "mem://$memKey"
    }

    private fun decryptMp4(mp4: ByteArray): ByteArray {
        val moovSlice = findBoxWithSlice(mp4, "moov", 0, mp4.size) ?: return mp4
        val keyUriRaw = extractKeyUriFromMoov(moovSlice.first) ?: return mp4
        val mediaKey = deriveMediaKey(keyUriRaw)
        val out = mp4.copyOf()

        val tables = collectTrakTables(moovSlice.first)
        val samples = mutableListOf<Pair<Int, Int>>()
        for ((stsz, chunk, stsc, co64) in tables) {
            samples.addAll(expandSamples(stsc, chunk, parseStsz(stsz), co64))
        }

        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(mediaKey, "AES"))
        for ((off, sz) in samples) {
            val aligned = sz and 0x7FFFFFF0
            if (off < 0 || off + aligned > out.size || aligned <= 0) continue
            val dec = cipher.doFinal(out, off, aligned)
            dec.copyInto(out, off, 0, minOf(aligned, dec.size))
        }
        return out
    }

    private fun md5HexSlice412(input: ByteArray): ByteArray {
        val h = MessageDigest.getInstance("MD5").digest(input)
        val sb = StringBuilder(16)
        for (i in 4 until 12) sb.append("%02x".format(h[i]))
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val aligned = data.size - (data.size % 16)
        if (aligned <= 0) return ByteArray(0)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data, 0, aligned)
    }

    private fun nativeUnpad(p: ByteArray): ByteArray {
        if (p.isEmpty()) return p
        val pad = p[p.size - 1].toInt() and 0xFF
        if (pad < 1 || pad > 16 || pad > p.size) return p
        val len = p.size - pad
        return if (len <= 0) ByteArray(0) else p.copyOfRange(0, len)
    }

    private fun deriveMediaKey(keyUriInput: ByteArray): ByteArray {
        var enc = keyUriInput
        val asStr = String(keyUriInput, Charsets.UTF_8).trim().replace(Regex("\\s+"), "")
        if (asStr.isNotEmpty() && asStr.matches(Regex("^[A-Za-z0-9+/=]+$"))) {
            runCatching { Base64.decode(asStr, Base64.DEFAULT) }.getOrNull()?.let { dec ->
                if (dec.isNotEmpty() && dec.size % 16 == 0) enc = dec
            }
        }
        val kuKey = md5HexSlice412(LICENSE_KEY.toByteArray(Charsets.US_ASCII))
        val plain = nativeUnpad(aesCbcDecrypt(enc, kuKey, kuKey))
        val text = String(plain, Charsets.UTF_8)
        val s = text.indexOf('{')
        val e = text.lastIndexOf('}')
        val obj = JSONObject(text.substring(s, e + 1))

        val cr = obj.getString("ClientRand").toByteArray(Charsets.UTF_8)
        val sr = Base64.decode(obj.getString("ServerRand"), Base64.DEFAULT)
        val pt = Base64.decode(obj.getString("PlainText"), Base64.DEFAULT)

        val key1 = md5HexSlice412(cr)
        val decSr = nativeUnpad(aesCbcDecrypt(sr, key1, key1))
        val concat = ByteArray(cr.size + decSr.size)
        cr.copyInto(concat, 0)
        decSr.copyInto(concat, cr.size)
        val key2 = md5HexSlice412(concat)
        val outB64 = String(nativeUnpad(aesCbcDecrypt(pt, key2, key1)), Charsets.UTF_8).trim()
        return Base64.decode(outB64, Base64.DEFAULT)
    }

    private fun extractKeyUriFromMoov(moov: ByteArray): ByteArray? {
        val keysBox = findBoxWithSlice(moov, "keys", 0, moov.size)?.first ?: return null
        if (keysBox.size < 8) return null
        val count = u32(keysBox, 4)
        var p = 8
        var targetIdx = -1
        for (i in 0 until count) {
            if (p + 4 > keysBox.size) break
            val entrySize = u32(keysBox, p)
            if (entrySize < 8 || p + entrySize > keysBox.size) break
            val name = String(keysBox, p + 8, entrySize - 8, Charsets.UTF_8)
            if (name.contains("AliyunPrivateKeyUri")) {
                targetIdx = i + 1
                break
            }
            p += entrySize
        }
        if (targetIdx < 0) return null

        val ilst = findBoxWithSlice(moov, "ilst", 0, moov.size)?.first ?: return null
        var q = 0
        while (q + 8 <= ilst.size) {
            val sz = u32(ilst, q)
            if (sz < 8 || q + sz > ilst.size) break
            val typIdx = u32(ilst, q + 4)
            if (typIdx == targetIdx) {
                val child = ilst.copyOfRange(q + 8, q + sz)
                if (child.size >= 16 && fourcc(child, 4) == "data") {
                    return child.copyOfRange(16, child.size)
                }
            }
            q += sz
        }
        return null
    }

    private fun findBoxWithSlice(data: ByteArray, type: String, start: Int, end: Int): Pair<ByteArray, Int>? {
        var i = start
        while (i + 8 <= end) {
            val sz = u32(data, i)
            if (sz < 8 || i + sz > end) return null
            val t = fourcc(data, i + 4)
            if (t == type) return data.copyOfRange(i + 8, i + sz) to (i + sz)
            if (t in listOf("moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "meta")) {
                val skip = if (t == "meta") 12 else 8
                val r = findBoxWithSlice(data, type, i + skip, i + sz)
                if (r != null) return r
            }
            i += sz
        }
        return null
    }

    private fun collectTrakTables(moovPayload: ByteArray): List<Quad> {
        val out = mutableListOf<Quad>()
        var i = 0
        while (i + 8 <= moovPayload.size) {
            val sz = u32(moovPayload, i)
            if (sz < 8 || i + sz > moovPayload.size) break
            if (fourcc(moovPayload, i + 4) == "trak") {
                val trak = moovPayload.copyOfRange(i + 8, i + sz)
                val stsz = findBoxWithSlice(trak, "stsz", 0, trak.size)?.first
                val stsc = findBoxWithSlice(trak, "stsc", 0, trak.size)?.first
                val stco = findBoxWithSlice(trak, "stco", 0, trak.size)?.first
                val co64 = findBoxWithSlice(trak, "co64", 0, trak.size)?.first
                val chunk = stco ?: co64
                if (stsz != null && stsc != null && chunk != null) {
                    out.add(Quad(stsz, chunk, stsc, stco == null))
                }
            }
            i += sz
        }
        return out
    }

    private fun parseStsz(stsz: ByteArray): List<Int> {
        val sampleSize = u32(stsz, 4)
        val count = u32(stsz, 8)
        if (sampleSize != 0) return List(count) { sampleSize }
        return List(count) { i -> u32(stsz, 12 + i * 4) }
    }

    private fun expandSamples(stsc: ByteArray, stco: ByteArray, sizes: List<Int>, co64: Boolean): List<Pair<Int, Int>> {
        val stscCount = u32(stsc, 4)
        val stcoCount = u32(stco, 4)
        val chunkN = IntArray(stcoCount)
        for (i in 0 until stscCount) {
            val o = 8 + i * 12
            if (o + 12 > stsc.size) break
            val first = u32(stsc, o)
            val spc = u32(stsc, o + 4)
            val start = first - 1
            val end = if (i + 1 < stscCount) u32(stsc, 8 + (i + 1) * 12) - 1 else stcoCount
            for (c in start until minOf(end, stcoCount)) chunkN[c] = spc
        }
        val out = mutableListOf<Pair<Int, Int>>()
        var si = 0
        for (c in 0 until stcoCount) {
            if (si >= sizes.size) break
            var base = if (co64) u64(stco, 8 + c * 8).toInt() else u32(stco, 8 + c * 4)
            for (s in 0 until chunkN[c]) {
                if (si >= sizes.size) break
                out.add(base to sizes[si])
                base += sizes[si]
                si++
            }
        }
        return out
    }

    private data class Quad(val stsz: ByteArray, val chunk: ByteArray, val stsc: ByteArray, val co64: Boolean)

    private fun fourcc(b: ByteArray, o: Int): String =
        String(byteArrayOf(b[o], b[o + 1], b[o + 2], b[o + 3]), Charsets.US_ASCII)

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long =
        ((u32(b, o).toLong() and 0xFFFFFFFFL) shl 32) or (u32(b, o + 4).toLong() and 0xFFFFFFFFL)
}
