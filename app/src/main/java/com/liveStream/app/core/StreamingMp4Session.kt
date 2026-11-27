package com.liveStream.app.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

class StreamPlan(
    val sanitizedMoov: ByteArray,
    val encryptedRanges: List<LongRange>,
    val decrypt: (ByteArray, Long) -> Unit
)

/**
 * Sumber progressive tanpa download penuh: unduh sekuensial di background,
 * dekripsi per rentang sample, lalu sajikan lewat ring buffer in-memory.
 */
class StreamingMp4Session(
    private val rawUrl: String,
    private val client: OkHttpClient,
    private val headers: Map<String, String>,
    maxBufferBytes: Int = 16 * 1024 * 1024,
    private val planFactory: (ByteArray) -> StreamPlan
) : ProgressiveMediaSource {

    @Volatile
    private var totalSize: Long = -1L
    override val totalLength: Long get() = totalSize

    private val buffer = ChunkedStreamBuffer(maxBufferBytes)

    @Volatile
    private var call: okhttp3.Call? = null

    @Volatile
    private var producer: Thread? = null

    fun start() {
        val req = Request.Builder()
            .url(rawUrl)
            .header("Accept-Encoding", "identity")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        val c = client.newCall(req)
        call = c
        val resp = c.execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IOException("stream HTTP ${resp.code}")
        }
        totalSize = resp.header("Content-Length")?.toLongOrNull() ?: -1L
        if (totalSize <= 0L) {
            resp.close()
            throw IOException("stream length unknown")
        }
        val body = resp.body ?: run {
            resp.close()
            throw IOException("stream body empty")
        }
        val source = body.source()
        try {
            var pos = 0L
            var plan: StreamPlan? = null
            val prefix = ByteArrayOutputStream()
            while (plan == null) {
                if (pos > MAX_PREFIX_BYTES) {
                    throw IOException("moov too far from start; sequential streaming unsupported")
                }
                val header = readHeader(source) ?: throw EOFException("EOF before moov")
                if (header.type == "mdat") {
                    throw IOException("mdat before moov; sequential streaming unsupported")
                }
                if (header.type == "moov") {
                    val payload = readExact(source, header.size - header.headerLen)
                    if (payload.size.toLong() != header.size - header.headerLen) {
                        throw EOFException("incomplete moov payload")
                    }
                    val moov = ByteArray(header.headerLen + payload.size)
                    header.bytes.copyInto(moov, 0)
                    payload.copyInto(moov, header.headerLen)
                    val p = planFactory(moov)
                    if (p.sanitizedMoov.size != moov.size) {
                        throw IOException("sanitized moov size mismatch")
                    }
                    prefix.write(p.sanitizedMoov)
                    pos += header.size
                    plan = p
                } else {
                    prefix.write(header.bytes)
                    copyRaw(source, header.size - header.headerLen, prefix)
                    pos += header.size
                }
            }
            buffer.write(prefix.toByteArray())
            val ready = plan
            producer = thread(name = "live-stream", isDaemon = true) {
                try {
                    streamRemaining(source, pos, ready)
                    buffer.finish()
                } catch (e: Throwable) {
                    buffer.fail(e)
                } finally {
                    resp.close()
                }
            }
        } catch (e: Throwable) {
            resp.close()
            buffer.fail(e)
            throw e
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        this.buffer.readAt(position, buffer, offset, length)

    override fun close() {
        call?.cancel()
        buffer.cancel()
        producer?.interrupt()
    }

    private fun streamRemaining(source: BufferedSource, startPos: Long, plan: StreamPlan) {
        var pos = startPos
        for (range in plan.encryptedRanges) {
            val rs = range.first
            val re = range.last + 1L
            if (re <= pos) continue
            if (rs > pos) {
                copyRaw(source, rs - pos, null)
                pos = rs
            }
            if (rs < pos) continue
            val want = re - pos
            val enc = readExact(source, want)
            if (enc.size.toLong() != want) throw EOFException("incomplete encrypted range")
            plan.decrypt(enc, pos)
            writeChunked(enc)
            pos += enc.size
        }
        copyRaw(source, Long.MAX_VALUE, null)
    }

    private data class BoxHeader(
        val bytes: ByteArray,
        val size: Long,
        val headerLen: Int,
        val type: String
    )

    private fun readHeader(source: BufferedSource): BoxHeader? {
        if (!source.request(8)) return null
        val h8 = source.readByteArray(8)
        var size = u32(h8, 0).toLong() and 0xFFFFFFFFL
        val type = String(h8, 4, 4, Charsets.US_ASCII)
        var headerLen = 8
        var bytes = h8
        if (size == 1L) {
            if (!source.request(8)) throw EOFException("EOF reading largesize")
            val ext = source.readByteArray(8)
            size = u64(ext, 0)
            headerLen = 16
            bytes = h8 + ext
        }
        if (size == 0L) throw IOException("box size 0 unsupported for $type")
        if (size < headerLen) throw IOException("invalid box size $size for $type")
        return BoxHeader(bytes, size, headerLen, type)
    }

    private fun readExact(source: BufferedSource, count: Long): ByteArray {
        if (count <= 0L) return ByteArray(0)
        val tmp = Buffer()
        var remaining = count
        while (remaining > 0L) {
            val n = source.read(tmp, remaining)
            if (n == -1L) break
            remaining -= n
        }
        return tmp.readByteArray()
    }

    private fun writeChunked(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val n = minOf(data.size - off, CHUNK)
            buffer.write(data.copyOfRange(off, off + n))
            off += n
        }
    }

    private fun copyRaw(source: BufferedSource, count: Long, sink: ByteArrayOutputStream?) {
        var remaining = count
        val tmp = Buffer()
        while (count == Long.MAX_VALUE || remaining > 0L) {
            val want = if (count == Long.MAX_VALUE) CHUNK.toLong() else minOf(remaining, CHUNK.toLong())
            val n = source.read(tmp, want)
            if (n == -1L) break
            val out = tmp.readByteArray()
            if (sink != null) sink.write(out) else buffer.write(out)
            if (count != Long.MAX_VALUE) remaining -= n
        }
    }

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long =
        ((u32(b, o).toLong() and 0xFFFFFFFFL) shl 32) or (u32(b, o + 4).toLong() and 0xFFFFFFFFL)

    private companion object {
        const val CHUNK = 1024 * 1024
        const val MAX_PREFIX_BYTES = 8L * 1024 * 1024
    }
}

private class ChunkedStreamBuffer(
    private val maxBytes: Int,
    private val historyBytes: Int = 2 * 1024 * 1024
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val notFull = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()

    private var baseOffset = 0L
    private var endOffset = 0L
    private var consumerPos = 0L
    private var closed = false
    private var error: Throwable? = null

    private fun buffered(): Long = endOffset - baseOffset

    fun write(data: ByteArray) {
        if (data.isEmpty()) return
        lock.lock()
        try {
            while (true) {
                error?.let { throw IOException(it) }
                if (closed) throw IOException("stream closed")
                if (buffered() + data.size <= maxBytes) break
                compactLocked()
                if (buffered() + data.size <= maxBytes) break
                notFull.await(200, TimeUnit.MILLISECONDS)
            }
            chunks.addLast(data)
            endOffset += data.size
            notEmpty.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun readAt(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        lock.lock()
        try {
            if (position > consumerPos) consumerPos = position
            while (true) {
                error?.let { throw IOException(it) }
                if (position < baseOffset) throw IOException("seek beyond stream buffer")
                if (position < endOffset) break
                if (closed) return -1
                notEmpty.await(500, TimeUnit.MILLISECONDS)
            }
            var p = position
            var copied = 0
            var chunkStart = baseOffset
            for (c in chunks) {
                val cEnd = chunkStart + c.size
                if (p >= chunkStart && p < cEnd) {
                    val inOff = (p - chunkStart).toInt()
                    val take = minOf(length - copied, c.size - inOff)
                    System.arraycopy(c, inOff, dst, offset + copied, take)
                    copied += take
                    p += take
                    if (copied == length) break
                }
                chunkStart = cEnd
            }
            if (p > consumerPos) consumerPos = p
            return if (copied > 0) copied else 0
        } finally {
            lock.unlock()
        }
    }

    fun finish() {
        lock.lock()
        try {
            closed = true
            notEmpty.signalAll()
            notFull.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun cancel() {
        lock.lock()
        try {
            closed = true
            notEmpty.signalAll()
            notFull.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun fail(t: Throwable) {
        lock.lock()
        try {
            if (!closed) error = t
            closed = true
            notEmpty.signalAll()
            notFull.signalAll()
        } finally {
            lock.unlock()
        }
    }

    private fun compactLocked() {
        val dropBefore = consumerPos - historyBytes
        while (chunks.size > 1) {
            val first = chunks.peekFirst() ?: break
            if (baseOffset + first.size <= dropBefore) {
                chunks.removeFirst()
                baseOffset += first.size
            } else {
                break
            }
        }
    }
}
