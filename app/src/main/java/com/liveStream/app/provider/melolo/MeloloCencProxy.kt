package com.liveStream.app.provider.melolo

import android.util.Base64
import android.util.Log
import com.liveStream.app.core.InMemoryMediaStore
import com.liveStream.app.core.ProgressiveMp4Session
import com.liveStream.app.core.StreamPlan
import com.liveStream.app.core.StreamingMp4Session
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dekriptor CENC (AES-128-CTR) progressive-on-the-fly (`prog://`) untuk stream Melolo (`spade_a`).
 * Tanpa ServerSocket dan tanpa download penuh 1 episode sebelum putar.
 */
object MeloloCencProxy {

    private const val TAG = "MeloloCenc"

    private data class CencSample(val offset: Long, val size: Int, val iv16: ByteArray, val ctrStartByte: Int = 0)

    fun preparePlayableUrl(
        cacheKey: String,
        rawUrl: String,
        spadeA: String,
        client: OkHttpClient
    ): String {
        if (spadeA.isBlank()) return rawUrl
        val progKey = "melolo_$cacheKey.mp4"
        if (InMemoryMediaStore.getProgressive(progKey) != null) return "prog://$progKey"
        val memKey = "$cacheKey.mp4"
        if (InMemoryMediaStore.get(memKey) != null) return "mem://$memKey"

        val aesKey = extractKeyFromSpadeA(spadeA) ?: return rawUrl
        val headers = mapOf("User-Agent" to "okhttp/3.10.0.1", "Accept" to "*/*")

        // 1. Jalur utama: Progressive Range decrypt-on-the-fly (hanya ambil moov ~512KB)
        val progResult = runCatching {
            val session = ProgressiveMp4Session(rawUrl, client, headers)
            val (moovRaw, moovStart) = session.locateMoov()
            val (sanitizedMoov, samples) = parseAndSanitizeMoov(moovRaw)
            if (samples.isEmpty()) error("no cenc samples")
            val keySpec = SecretKeySpec(aesKey, "AES")
            session.attachMoovAndDecryptor(sanitizedMoov, moovStart) { chunk, fileOff ->
                decryptChunkCtrInPlace(chunk, fileOff, samples, keySpec)
            }
            InMemoryMediaStore.putProgressive(progKey, session)
            "prog://$progKey"
        }.onFailure { Log.d(TAG, "progressive failed: ${it.message}") }.getOrNull()
        if (progResult != null) return progResult

        // 2. Fallback streaming: pipe/ring-buffer tanpa download penuh
        val streamKey = "melolo_${cacheKey}_stream.mp4"
        if (InMemoryMediaStore.getProgressive(streamKey) != null) return "prog://$streamKey"
        val streamResult = runCatching {
            val session = StreamingMp4Session(rawUrl, client, headers) { moovRaw ->
                val (sanitizedMoov, samples) = parseAndSanitizeMoov(moovRaw)
                if (samples.isEmpty()) error("no cenc samples")
                val keySpec = SecretKeySpec(aesKey, "AES")
                StreamPlan(
                    sanitizedMoov = sanitizedMoov,
                    encryptedRanges = samples.map { it.offset until (it.offset + it.size) },
                    decrypt = { bytes, offset ->
                        decryptChunkCtrInPlace(bytes, offset, samples, keySpec)
                    }
                )
            }
            session.start()
            InMemoryMediaStore.putProgressive(streamKey, session)
            "prog://$streamKey"
        }.onFailure { Log.d(TAG, "streaming fallback failed: ${it.message}") }.getOrNull()
        if (streamResult != null) return streamResult

        // 3. Last resort: download penuh (moov di ekor / server tanpa Range)
        Log.d(TAG, "falling back to full download for $cacheKey")
        val req = Request.Builder()
            .url(rawUrl)
            .header("User-Agent", "okhttp/3.10.0.1")
            .header("Accept", "*/*")
            .build()
        val rawBytes = runCatching {
            client.newCall(req).execute().use { it.body?.bytes() }
        }.getOrNull() ?: return rawUrl
        if (rawBytes.isEmpty()) return rawUrl

        val clean = decryptMp4Bytes(rawBytes, aesKey)
        if (clean.isEmpty()) return rawUrl
        InMemoryMediaStore.put(memKey, clean)
        return "mem://$memKey"
    }

    private fun parseAndSanitizeMoov(moovRaw: ByteArray): Pair<ByteArray, List<CencSample>> {
        val moov = moovRaw.copyOf()
        val samples = mutableListOf<CencSample>()
        var pos = findBox(moov, "trak", 0, moov.size)
        while (pos >= 0) {
            val trakSize = readU32(moov, pos - 4)
            val trakEnd = if (trakSize > 8 && pos - 4 + trakSize <= moov.size) pos - 4 + trakSize else moov.size
            samples.addAll(extractTrackSamples(moov, pos, trakEnd))
            pos = findBox(moov, "trak", trakEnd, moov.size)
        }
        samples.sortBy { it.offset }

        val hasAv1C = findBox(moov, "av1C", 0, moov.size) >= 0
        val hasHvcC = findBox(moov, "hvcC", 0, moov.size) >= 0
        val hasAvcC = findBox(moov, "avcC", 0, moov.size) >= 0
        val fourCC = when {
            hasAv1C && !hasAvcC && !hasHvcC -> "av01"
            hasHvcC && !hasAvcC -> "hvc1"
            else -> "avc1"
        }
        replaceBoxType(moov, "encv", fourCC, 0, moov.size)
        replaceBoxType(moov, "enca", "mp4a", 0, moov.size)
        for (drmBox in listOf("sinf", "senc", "saio", "saiz", "schm", "schi", "tenc", "pssh")) {
            replaceBoxType(moov, drmBox, "free", 0, moov.size)
        }
        return moov to samples
    }

    private fun extractTrackSamples(moov: ByteArray, trakPos: Int, trakEnd: Int): List<CencSample> {
        val stszPos = findBox(moov, "stsz", trakPos, trakEnd)
        val stcoPos = findBox(moov, "stco", trakPos, trakEnd)
        val stscPos = findBox(moov, "stsc", trakPos, trakEnd)
        val sencPos = findBox(moov, "senc", trakPos, trakEnd)
        if (stszPos < 0 || stcoPos < 0 || stscPos < 0 || sencPos < 0) return emptyList()

        val sencFlags = readU32(moov, sencPos + 4) and 0x00FFFFFF
        val hasSubsamples = (sencFlags and 0x02) != 0

        val fixedSize = readU32(moov, stszPos + 8)
        val scnt = readU32(moov, stszPos + 12)
        val ivCnt = readU32(moov, sencPos + 8)
        if (scnt <= 0 || ivCnt != scnt) return emptyList()
        val sizes = IntArray(scnt) { i ->
            if (fixedSize != 0) fixedSize else readU32(moov, stszPos + 16 + i * 4)
        }

        val ccnt = readU32(moov, stcoPos + 8)
        val chunks = IntArray(ccnt) { i -> readU32(moov, stcoPos + 12 + i * 4) }
        val ecnt = readU32(moov, stscPos + 8)
        val stsc = Array(ecnt) { i ->
            val b = stscPos + 12 + i * 12
            intArrayOf(readU32(moov, b), readU32(moov, b + 4))
        }

        val sampleOffsets = LongArray(scnt)
        var sIdx = 0
        for (cIdx in 0 until ccnt) {
            val chunkNo = cIdx + 1
            var spc = stsc.firstOrNull()?.get(1) ?: 1
            for (e in stsc) if (chunkNo >= e[0]) spc = e[1]
            var off = chunks[cIdx].toLong() and 0xFFFFFFFFL
            for (s in 0 until spc) {
                if (sIdx < scnt) {
                    sampleOffsets[sIdx] = off
                    off += sizes[sIdx]
                    sIdx++
                }
            }
        }

        // Deteksi Per_Sample_IV_Size (8 atau 16 byte) dari box tenc agar tidak meleset
        val tencPos = findBox(moov, "tenc", trakPos, trakEnd)
        val ivSize = if (tencPos >= 0 && tencPos + 12 < trakEnd) {
            val sz = moov[tencPos + 11].toInt() and 0xFF
            if (sz == 16) 16 else 8
        } else {
            8
        }

        val out = ArrayList<CencSample>(scnt)
        var sencCursor = sencPos + 12
        for (i in 0 until scnt) {
            if (sencCursor + ivSize > moov.size) return emptyList()
            val iv16 = ByteArray(16)
            moov.copyInto(iv16, 0, sencCursor, sencCursor + ivSize)
            sencCursor += ivSize
            val baseOff = sampleOffsets[i]
            val sampleSize = sizes[i]
            if (!hasSubsamples) {
                if (sampleSize > 0) {
                    out.add(CencSample(baseOff, sampleSize, iv16, ctrStartByte = 0))
                }
            } else {
                if (sencCursor + 2 > moov.size) return emptyList()
                val subCount = readU16(moov, sencCursor)
                sencCursor += 2
                var curOff = baseOff
                var ctrByte = 0
                for (sub in 0 until subCount) {
                    if (sencCursor + 6 > moov.size) return emptyList()
                    val clearBytes = readU16(moov, sencCursor)
                    val encBytes = readU32(moov, sencCursor + 2)
                    sencCursor += 6
                    curOff += clearBytes
                    if (encBytes > 0) {
                        out.add(CencSample(curOff, encBytes, iv16, ctrStartByte = ctrByte))
                        curOff += encBytes
                        ctrByte += encBytes
                    }
                }
            }
        }
        return out
    }

    private fun addCounterToIv(baseIv16: ByteArray, blockDelta: Long, outIv16: ByteArray) {
        baseIv16.copyInto(outIv16, 0, 0, 16)
        if (blockDelta == 0L) return
        var carry = blockDelta
        var i = 15
        while (i >= 8 && carry > 0L) {
            val sum = (outIv16[i].toInt() and 0xFF) + (carry and 0xFFL)
            outIv16[i] = (sum and 0xFFL).toByte()
            carry = (carry ushr 8) + (sum ushr 8)
            i--
        }
    }

    private fun decryptChunkCtrInPlace(
        chunk: ByteArray,
        fileOffset: Long,
        samples: List<CencSample>,
        keySpec: SecretKeySpec
    ) {
        if (chunk.isEmpty() || samples.isEmpty()) return
        val rangeStart = fileOffset
        val rangeEnd = fileOffset + chunk.size
        val ctrCipher = Cipher.getInstance("AES/CTR/NoPadding")
        val counterBlock = ByteArray(16)
        val scratchBlock = ByteArray(16)

        // Binary search first sample with offset + size > rangeStart
        var lo = 0
        var hi = samples.size - 1
        var firstIdx = samples.size
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = samples[mid]
            if (s.offset + s.size > rangeStart) {
                firstIdx = mid
                hi = mid - 1
            } else {
                lo = mid + 1
            }
        }

        for (idx in firstIdx until samples.size) {
            val s = samples[idx]
            if (s.offset >= rangeEnd) break
            val sEnd = s.offset + s.size
            val overlapStart = maxOf(s.offset, rangeStart)
            val overlapEnd = minOf(sEnd, rangeEnd)
            var overlapLen = (overlapEnd - overlapStart).toInt()
            var outPos = (overlapStart - rangeStart).toInt()
            var posInSample = s.ctrStartByte + (overlapStart - s.offset).toInt()

            val blockIdx = (posInSample / 16).toLong()
            val blockOff = posInSample % 16
            addCounterToIv(s.iv16, blockIdx, counterBlock)
            ctrCipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(counterBlock))

            if (blockOff != 0) {
                // Skip prefix bytes inside the first 16-byte CTR block without per-block ECB calls
                scratchBlock.fill(0)
                val headTake = minOf(16 - blockOff, overlapLen)
                chunk.copyInto(scratchBlock, blockOff, outPos, outPos + headTake)
                val decHead = ctrCipher.update(scratchBlock, 0, 16)
                if (decHead != null && decHead.size >= blockOff + headTake) {
                    decHead.copyInto(chunk, outPos, blockOff, blockOff + headTake)
                }
                outPos += headTake
                overlapLen -= headTake
            }
            if (overlapLen > 0) {
                ctrCipher.doFinal(chunk, outPos, overlapLen, chunk, outPos)
            }
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

        val fixedSize = readU32(data, stszPos + 8)
        val scnt = readU32(data, stszPos + 12)
        val ivCnt = readU32(data, sencPos + 8)
        if (scnt <= 0 || ivCnt != scnt) return
        val sizes = IntArray(scnt) { i ->
            if (fixedSize != 0) fixedSize else readU32(data, stszPos + 16 + i * 4)
        }

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

        val sencFlags = readU32(data, sencPos + 4) and 0x00FFFFFF
        val hasSubsamples = (sencFlags and 0x02) != 0
        var sencCursor = sencPos + 12
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        val iv16 = ByteArray(16)

        for (i in 0 until scnt) {
            if (sencCursor + 8 > data.size) break
            val off = sampleOffsets[i]
            val sz = sizes[i]
            System.arraycopy(data, sencCursor, iv16, 0, 8)
            for (k in 8 until 16) iv16[k] = 0
            sencCursor += 8

            if (!hasSubsamples) {
                if (off >= 0 && off + sz <= data.size && sz > 0) {
                    cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv16))
                    val dec = cipher.doFinal(data, off, sz)
                    System.arraycopy(dec, 0, data, off, minOf(sz, dec.size))
                }
            } else {
                if (sencCursor + 2 > data.size) break
                val subCount = readU16(data, sencCursor)
                sencCursor += 2
                cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv16))
                var sampleCur = off
                for (sub in 0 until subCount) {
                    if (sencCursor + 6 > data.size) break
                    val clearBytes = readU16(data, sencCursor)
                    val encBytes = readU32(data, sencCursor + 2)
                    sencCursor += 6
                    sampleCur += clearBytes
                    if (encBytes > 0 && sampleCur >= 0 && sampleCur + encBytes <= data.size) {
                        val dec = cipher.update(data, sampleCur, encBytes)
                        if (dec != null) {
                            System.arraycopy(dec, 0, data, sampleCur, minOf(encBytes, dec.size))
                        }
                        sampleCur += encBytes
                    }
                }
            }
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

    private fun readU16(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)

    private fun readU32(data: ByteArray, off: Int): Int =
        ((data[off].toInt() and 0xFF) shl 24) or
            ((data[off + 1].toInt() and 0xFF) shl 16) or
            ((data[off + 2].toInt() and 0xFF) shl 8) or
            (data[off + 3].toInt() and 0xFF)
}
