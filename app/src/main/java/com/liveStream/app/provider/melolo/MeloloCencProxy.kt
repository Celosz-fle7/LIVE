package com.liveStream.app.provider.melolo

import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dekriptor CENC (AES-128-CTR) + HTTP 206 loopback proxy untuk stream Melolo (`spade_a`).
 */
object MeloloCencProxy {
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val executor = Executors.newCachedThreadPool()
    private var serverPort: Int = -1
    private val lock = Any()

    fun preparePlayableUrl(
        cacheKey: String,
        rawUrl: String,
        spadeA: String,
        client: OkHttpClient
    ): String {
        if (spadeA.isBlank()) return rawUrl
        val aesKey = extractKeyFromSpadeA(spadeA) ?: return rawUrl

        val decrypted = cache[cacheKey] ?: run {
            val req = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", "okhttp/3.10.0.1")
                .header("Accept", "*/*")
                .build()
            val rawBytes = client.newCall(req).execute().use { it.body?.bytes() } ?: return rawUrl
            val clean = decryptMp4Bytes(rawBytes, aesKey)
            if (cache.size > 6) cache.clear()
            cache[cacheKey] = clean
            clean
        }
        if (decrypted.isEmpty()) return rawUrl
        val port = ensureServerStarted()
        return "http://127.0.0.1:$port/$cacheKey.mp4"
    }

    private fun ensureServerStarted(): Int {
        if (serverPort > 0) return serverPort
        synchronized(lock) {
            if (serverPort > 0) return serverPort
            val ss = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            serverPort = ss.localPort
            executor.execute {
                while (!ss.isClosed) {
                    val sock = runCatching { ss.accept() }.getOrNull() ?: continue
                    executor.execute { handleSocket(sock) }
                }
            }
            return serverPort
        }
    }

    private fun handleSocket(sock: java.net.Socket) {
        sock.use { s ->
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val reqLine = reader.readLine() ?: return
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val path = reqLine.split(" ").getOrNull(1).orEmpty().trimStart('/').removeSuffix(".mp4")
            val data = cache[path]
            val out = s.getOutputStream()
            if (data == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            val total = data.size
            var start = 0
            var end = total - 1
            var isRange = false
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                isRange = true
                val parts = rangeHeader.removePrefix("bytes=").split("-")
                start = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, total - 1) ?: 0
                end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull()?.coerceIn(start, total - 1)
                    ?: (total - 1)
            }

            val len = (end - start + 1).coerceAtLeast(0)
            val hdr = buildString {
                if (isRange) {
                    append("HTTP/1.1 206 Partial Content\r\n")
                    append("Content-Range: bytes $start-$end/$total\r\n")
                } else {
                    append("HTTP/1.1 200 OK\r\n")
                }
                append("Content-Type: video/mp4\r\n")
                append("Accept-Ranges: bytes\r\n")
                append("Content-Length: $len\r\n")
                append("Connection: close\r\n\r\n")
            }
            out.write(hdr.toByteArray())
            out.write(data, start, len)
            out.flush()
        }
    }

    private fun extractKeyFromSpadeA(spadeABase64: String): ByteArray? = runCatching {
        val raw = Base64.decode(spadeABase64.trim(), Base64.DEFAULT)
        if (raw.size < 8) return null
        val paddingLen = ((raw[0].toInt() and 0xFF) xor (raw[1].toInt() and 0xFF) xor (raw[2].toInt() and 0xFF)) - 48
        if (paddingLen < 0 || paddingLen >= raw.size) return null

        val inner = raw.copyOfRange(1, raw.size - paddingLen)
        val buf = ByteArray(2 + inner.size)
        buf[0] = 0xFA.toByte()
        buf[1] = 0x55
        inner.copyInto(buf, 2)

        val decoded = ByteArray(inner.size)
        for (i in inner.indices) {
            var v = ((inner[i].toInt() and 0xFF) xor (buf[i].toInt() and 0xFF)) - Integer.bitCount(i) - 21
            while (v < 0) v += 0xFF
            decoded[i] = (v % 256).toByte()
        }

        val cp = decoded[0].toInt() and 0xFF
        val skip = when (cp) {
            in 48..57 -> cp - 48
            in 97..122 -> cp - 97 + 10
            else -> 0xFF
        }
        val msgLen = raw.size - paddingLen - 2
        val endIdx = 1 + msgLen - skip
        if (endIdx <= 1 || endIdx > decoded.size) return null

        val hex = String(decoded, 1, endIdx - 1, Charsets.UTF_8).replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 32) return null
        ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }.getOrNull()

    private fun decryptMp4Bytes(mp4: ByteArray, aesKey: ByteArray): ByteArray {
        if (mp4.size < 100) return mp4
        val data = mp4.copyOf()
        val vTrak = findBox(data, "trak", 0, data.size)
        if (vTrak < 0) return data
        val aTrak = findBox(data, "trak", vTrak + 4, data.size)

        val keySpec = SecretKeySpec(aesKey, "AES")
        decryptTrack(data, vTrak, if (aTrak > 0) aTrak else data.size, keySpec)
        if (aTrak > 0) decryptTrack(data, aTrak, data.size, keySpec)

        val moovPos = findBox(data, "moov", 0, data.size)
        var sStart = 0
        var sEnd = data.size
        if (moovPos >= 4) {
            val moovSize = readU32(data, moovPos - 4)
            if (moovSize > 8 && moovPos - 4 + moovSize <= data.size) {
                sStart = moovPos - 4
                sEnd = moovPos - 4 + moovSize
            }
        }

        val hasHvcC = findBox(data, "hvcC", sStart, sEnd) >= 0
        val hasAvcC = findBox(data, "avcC", sStart, sEnd) >= 0
        val fourCC = if (hasHvcC && !hasAvcC) "hvc1" else "avc1"
        replaceBoxType(data, "encv", fourCC, sStart, sEnd)
        replaceBoxType(data, "enca", "mp4a", sStart, sEnd)
        for (drmBox in listOf("sinf", "senc", "saio", "saiz", "schm", "schi", "tenc", "pssh")) {
            replaceBoxType(data, drmBox, "free", sStart, sEnd)
        }
        return data
    }

    private fun decryptTrack(data: ByteArray, trakPos: Int, trakEnd: Int, keySpec: SecretKeySpec) {
        val stszPos = findBox(data, "stsz", trakPos, trakEnd)
        val stcoPos = findBox(data, "stco", trakPos, trakEnd)
        val stscPos = findBox(data, "stsc", trakPos, trakEnd)
        val sencPos = findBox(data, "senc", trakPos, trakEnd)
        if (stszPos < 0 || stcoPos < 0 || stscPos < 0 || sencPos < 0) return

        val scnt = readU32(data, stszPos + 12)
        val ivCnt = readU32(data, sencPos + 8)
        if (scnt <= 0 || ivCnt != scnt) return
        val sizes = IntArray(scnt) { i -> readU32(data, stszPos + 16 + i * 4) }

        val ccnt = readU32(data, stcoPos + 8)
        val chunks = IntArray(ccnt) { i -> readU32(data, stcoPos + 12 + i * 4) }

        val ecnt = readU32(data, stscPos + 8)
        val stsc = Array(ecnt) { i ->
            val b = stscPos + 12 + i * 12
            intArrayOf(readU32(data, b), readU32(data, b + 4))
        }

        val sampleOffsets = IntArray(scnt)
        var sIdx = 0
        for (cIdx in 0 until ccnt) {
            val chunkNo = cIdx + 1
            var spc = stsc.firstOrNull()?.get(1) ?: 1
            for (e in stsc) if (chunkNo >= e[0]) spc = e[1]
            var off = chunks[cIdx]
            for (s in 0 until spc) {
                if (sIdx < scnt) {
                    sampleOffsets[sIdx] = off
                    off += sizes[sIdx]
                    sIdx++
                }
            }
        }

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val iv16 = ByteArray(16)
        for (i in 0 until scnt) {
            val off = sampleOffsets[i]
            val sz = sizes[i]
            if (off < 0 || off + sz > data.size || sz <= 0) continue
            val ivOff = sencPos + 12 + i * 8
            System.arraycopy(data, ivOff, iv16, 0, 8)
            for (k in 8 until 16) iv16[k] = 0
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv16))
            val dec = cipher.doFinal(data, off, sz)
            System.arraycopy(dec, 0, data, off, minOf(sz, dec.size))
        }
    }

    private fun replaceBoxType(data: ByteArray, from: String, to: String, start: Int, end: Int) {
        var pos = start
        while (pos < end - 4) {
            val idx = findBox(data, from, pos, end)
            if (idx < 0) break
            data[idx] = to[0].code.toByte()
            data[idx + 1] = to[1].code.toByte()
            data[idx + 2] = to[2].code.toByte()
            data[idx + 3] = to[3].code.toByte()
            pos = idx + 4
        }
    }

    private fun findBox(data: ByteArray, boxType: String, start: Int, end: Int): Int {
        val b0 = boxType[0].code.toByte()
        val b1 = boxType[1].code.toByte()
        val b2 = boxType[2].code.toByte()
        val b3 = boxType[3].code.toByte()
        val limit = end - 4
        for (i in start until limit) {
            if (data[i] == b0 && data[i + 1] == b1 && data[i + 2] == b2 && data[i + 3] == b3) return i
        }
        return -1
    }

    private fun readU32(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 24) or
            ((data[off + 1].toInt() and 0xFF) shl 16) or
            ((data[off + 2].toInt() and 0xFF) shl 8) or
            (data[off + 3].toInt() and 0xFF)
}
