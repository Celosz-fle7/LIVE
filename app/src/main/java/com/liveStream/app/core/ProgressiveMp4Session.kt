package com.liveStream.app.core

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class RangeNotSupportedException(
    message: String = "HTTP Range not supported"
) : IOException(message)

/**
 * Shared progressive MP4 Range fetcher + fast moov scanner + 512KB chunk cache + async prefetch.
 * Dipakai bersama oleh Melolo (AES-128-CTR) dan DramaBox (Aliyun AES-128-ECB) tanpa download penuh.
 * Mendukung padding 16-byte di tepi chunk agar blok AES-ECB tidak pernah terbelah di batas chunk.
 */
class ProgressiveMp4Session(
    private val rawUrl: String,
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    private val chunkSize: Int = 512 * 1024,
    private val blockBoundaryPadding: Int = 16
) : ProgressiveMediaSource {

    var totalSize: Long = -1L
        private set
    override val totalLength: Long
        get() = totalSize

    var moovBytes: ByteArray = ByteArray(0)
        private set
    var moovStart: Long = -1L
        private set
    var moovEnd: Long = -1L
        private set

    private var decryptChunkFn: ((chunk: ByteArray, fileOffset: Long) -> Unit)? = null
    private val chunkCache = ConcurrentHashMap<Long, ByteArray>()
    private val inFlightChunks = ConcurrentHashMap.newKeySet<Long>()
    private val chunkOrder = ArrayDeque<Long>()

    @Volatile
    private var closed = false

    fun locateMoov(): Pair<ByteArray, Long> {
        // 1. Fast head probe (64KB dulu): jika ketemu mdat sebelum moov, langsung loncat ke tail tanpa buang 2MB
        val (head64k, total) = fetchRange(0L, (64 * 1024 - 1).toLong())
        if (total > 0) totalSize = total

        val headResult = scanHeadForMoov(head64k)
        if (headResult.found != null) return headResult.found

        // Jika belum ketemu mdat di 64KB pertama, perluas scan head ke 512KB
        if (!headResult.sawMdat && head64k.size == 64 * 1024) {
            val (head512k, total2) = fetchRange(0L, (512 * 1024 - 1).toLong())
            if (total2 > 0) totalSize = total2
            val res2 = scanHeadForMoov(head512k)
            if (res2.found != null) return res2.found
        }

        // 2. Scan tail bertahap (512KB -> 2MB -> 4MB)
        if (totalSize <= 0) totalSize = fetchTotalLength()
        if (totalSize <= 0) throw IOException("Cannot determine total length for $rawUrl")

        for (tailSize in longArrayOf(512L * 1024, 2L * 1024 * 1024, 4L * 1024 * 1024)) {
            val tailStart = (totalSize - tailSize).coerceAtLeast(0L)
            val (tail, _) = fetchRange(tailStart, totalSize - 1)
            val found = findMoovInTail(tail, tailStart)
            if (found != null) return found
            if (tailStart == 0L) break
        }
        throw IOException("moov box not found in head or tail")
    }

    fun attachMoovAndDecryptor(
        sanitizedMoov: ByteArray,
        moovStartPos: Long,
        decryptor: (chunk: ByteArray, fileOffset: Long) -> Unit
    ) {
        this.moovBytes = sanitizedMoov
        this.moovStart = moovStartPos
        this.moovEnd = moovStartPos + sanitizedMoov.size
        this.decryptChunkFn = decryptor
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed || position >= totalSize || length <= 0) return -1
        val maxRead = minOf(length.toLong(), totalSize - position).toInt()
        var copied = 0
        var curPos = position

        while (copied < maxRead) {
            // Serve directly from sanitized moov if overlapping
            if (moovStart >= 0 && curPos in moovStart until moovEnd) {
                val moovOff = (curPos - moovStart).toInt()
                val take = minOf(maxRead - copied, moovBytes.size - moovOff)
                moovBytes.copyInto(buffer, offset + copied, moovOff, moovOff + take)
                copied += take
                curPos += take
                continue
            }

            val chunkIdx = curPos / chunkSize
            val chunk = getOrFetchChunk(chunkIdx)
            prefetchNextChunk(chunkIdx + 1)
            val inChunkOff = (curPos - chunkIdx * chunkSize).toInt()
            if (inChunkOff >= chunk.size) break
            val take = minOf(maxRead - copied, chunk.size - inChunkOff)
            chunk.copyInto(buffer, offset + copied, inChunkOff, inChunkOff + take)
            copied += take
            curPos += take
        }
        return if (copied > 0) copied else -1
    }

    override fun close() {
        closed = true
    }

    private fun prefetchNextChunk(nextChunkIdx: Long) {
        if (closed || totalSize <= 0L) return
        val nextStart = nextChunkIdx * chunkSize
        if (nextStart >= totalSize) return
        if (chunkCache.containsKey(nextChunkIdx) || !inFlightChunks.add(nextChunkIdx)) return
        prefetchExecutor.execute {
            try {
                if (!closed && !chunkCache.containsKey(nextChunkIdx)) {
                    fetchAndStoreChunk(nextChunkIdx)
                }
            } catch (_: Throwable) {
            } finally {
                inFlightChunks.remove(nextChunkIdx)
            }
        }
    }

    private fun getOrFetchChunk(chunkIdx: Long): ByteArray {
        chunkCache[chunkIdx]?.let { return it }
        return fetchAndStoreChunk(chunkIdx)
    }

    private fun fetchAndStoreChunk(chunkIdx: Long): ByteArray {
        chunkCache[chunkIdx]?.let { return it }
        val start = chunkIdx * chunkSize
        val end = minOf(start + chunkSize - 1, totalSize - 1)
        val pad = blockBoundaryPadding.toLong().coerceAtLeast(0L)
        val paddedStart = (start - pad).coerceAtLeast(0L)
        val paddedEnd = minOf(end + pad, totalSize - 1)

        val (paddedBytes, _) = fetchRange(paddedStart, paddedEnd)
        decryptChunkFn?.invoke(paddedBytes, paddedStart)

        val sliceStart = (start - paddedStart).toInt().coerceIn(0, paddedBytes.size)
        val desiredLen = (end - start + 1).toInt()
        val sliceEnd = (sliceStart + desiredLen).coerceIn(sliceStart, paddedBytes.size)
        val bytes = if (sliceStart == 0 && sliceEnd == paddedBytes.size) {
            paddedBytes
        } else {
            paddedBytes.copyOfRange(sliceStart, sliceEnd)
        }

        synchronized(this) {
            if (!chunkCache.containsKey(chunkIdx)) {
                if (chunkOrder.size >= MAX_CACHED_CHUNKS) {
                    chunkCache.remove(chunkOrder.removeFirst())
                }
                chunkOrder.addLast(chunkIdx)
                chunkCache[chunkIdx] = bytes
            }
        }
        return bytes
    }

    private fun fetchTotalLength(): Long {
        val req = Request.Builder().url(rawUrl).head().apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { resp ->
            return resp.header("Content-Length")?.toLongOrNull() ?: -1L
        }
    }

    private fun fetchRange(start: Long, end: Long): Pair<ByteArray, Long> {
        val req = Request.Builder()
            .url(rawUrl)
            .header("Range", "bytes=$start-$end")
            .header("Accept-Encoding", "identity")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(req).execute().use { resp ->
            when (resp.code) {
                206 -> {
                    val cr = resp.header("Content-Range")
                        ?: throw IOException("Range response missing Content-Range")
                    val total = cr.substringAfterLast("/", "").toLongOrNull()
                        ?: throw IOException("Range response missing total size")
                    val body = resp.body?.bytes() ?: ByteArray(0)
                    return body to total
                }
                200 -> throw RangeNotSupportedException()
                else -> throw IOException("Range HTTP ${resp.code}")
            }
        }
    }

    private data class HeadScanResult(
        val found: Pair<ByteArray, Long>?,
        val sawMdat: Boolean
    )

    private fun scanHeadForMoov(head: ByteArray): HeadScanResult {
        var off = 0
        while (off + 8 <= head.size) {
            var size = u32(head, off).toLong() and 0xFFFFFFFFL
            val type = fourcc(head, off + 4)
            var hdr = 8
            if (size == 1L) {
                if (off + 16 > head.size) return HeadScanResult(null, false)
                size = u64(head, off + 8)
                hdr = 16
            } else if (size == 0L) {
                size = (head.size - off).toLong()
            }
            if (size < hdr) return HeadScanResult(null, false)
            if (type == "moov") {
                val end = off + size
                val pair = if (end <= head.size) {
                    head.copyOfRange(off, end.toInt()) to off.toLong()
                } else {
                    val (full, _) = fetchRange(off.toLong(), end - 1)
                    full to off.toLong()
                }
                return HeadScanResult(pair, false)
            }
            if (type == "mdat") return HeadScanResult(null, true)
            if (size > Int.MAX_VALUE) return HeadScanResult(null, false)
            off += size.toInt()
        }
        return HeadScanResult(null, false)
    }

    private fun findMoovInTail(tail: ByteArray, tailStart: Long): Pair<ByteArray, Long>? {
        var i = 0
        while (i + 8 <= tail.size) {
            if (tail[i + 4] == 'm'.code.toByte() &&
                tail[i + 5] == 'o'.code.toByte() &&
                tail[i + 6] == 'o'.code.toByte() &&
                tail[i + 7] == 'v'.code.toByte()
            ) {
                val sz = u32(tail, i).toLong() and 0xFFFFFFFFL
                if (sz in 64..(32L * 1024 * 1024)) {
                    val absStart = tailStart + i
                    val end = absStart + sz
                    if (end <= totalSize) {
                        return if (i + sz <= tail.size) {
                            tail.copyOfRange(i, (i + sz).toInt()) to absStart
                        } else {
                            val (full, _) = fetchRange(absStart, end - 1)
                            full to absStart
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun fourcc(b: ByteArray, o: Int): String =
        String(byteArrayOf(b[o], b[o + 1], b[o + 2], b[o + 3]), Charsets.US_ASCII)

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long =
        ((u32(b, o).toLong() and 0xFFFFFFFFL) shl 32) or (u32(b, o + 4).toLong() and 0xFFFFFFFFL)

    private companion object {
        const val MAX_CACHED_CHUNKS = 24
        val prefetchExecutor = Executors.newFixedThreadPool(2)
    }
}
