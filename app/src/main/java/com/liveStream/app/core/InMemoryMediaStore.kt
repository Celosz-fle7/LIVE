package com.liveStream.app.core

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

interface ProgressiveMediaSource {
    val totalLength: Long
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * Store in-memory + Media3 DataSource.Factory untuk stream terdekripsi (`mem://` dan `prog://`).
 * Menghilangkan kebutuhan ServerSocket / localhost HTTP proxy sepenuhnya.
 */
@UnstableApi
object InMemoryMediaStore {
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val progCache = ConcurrentHashMap<String, ProgressiveMediaSource>()
    private val insertionOrder = ArrayDeque<String>()
    private val progOrder = ArrayDeque<String>()

    @Synchronized
    fun put(key: String, data: ByteArray) {
        if (!cache.containsKey(key)) {
            if (insertionOrder.size >= 6) {
                cache.remove(insertionOrder.removeFirst())
            }
            insertionOrder.addLast(key)
        }
        cache[key] = data
    }

    fun get(key: String): ByteArray? = cache[key]

    @Synchronized
    fun putProgressive(key: String, source: ProgressiveMediaSource) {
        if (!progCache.containsKey(key)) {
            if (progOrder.size >= 8) {
                progCache.remove(progOrder.removeFirst())
            }
            progOrder.addLast(key)
        }
        progCache[key] = source
    }

    fun getProgressive(key: String): ProgressiveMediaSource? = progCache[key]

    fun createDataSourceFactory(headers: Map<String, String>): DataSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory().setDefaultRequestProperties(headers)
        return DataSource.Factory {
            RoutingDataSource(httpFactory.createDataSource())
        }
    }

    private class RoutingDataSource(
        private val httpDelegate: DataSource
    ) : DataSource {
        private var activeMem: ByteArrayDataSource? = null
        private var activeProg: ProgressiveMediaSource? = null
        private var progUri: android.net.Uri? = null
        private var progPos: Long = 0L
        private var progRemaining: Long = C.LENGTH_UNSET.toLong()
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners.add(transferListener)
            httpDelegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val scheme = dataSpec.uri.scheme
            if (scheme == "mem") {
                val cleanKey = (dataSpec.uri.host.orEmpty() + dataSpec.uri.path.orEmpty()).trimStart('/')
                val bytes = cache[cleanKey] ?: cache[dataSpec.uri.lastPathSegment.orEmpty()]
                    ?: throw IOException("InMemoryMediaStore miss for $cleanKey")
                val ds = ByteArrayDataSource(bytes)
                listeners.forEach { ds.addTransferListener(it) }
                activeMem = ds
                activeProg = null
                return ds.open(dataSpec)
            }
            if (scheme == "prog") {
                val cleanKey = (dataSpec.uri.host.orEmpty() + dataSpec.uri.path.orEmpty()).trimStart('/')
                val prog = progCache[cleanKey] ?: progCache[dataSpec.uri.lastPathSegment.orEmpty()]
                    ?: throw IOException("ProgressiveMediaSource miss for $cleanKey")
                activeMem = null
                activeProg = prog
                progUri = dataSpec.uri
                progPos = dataSpec.position.coerceAtLeast(0L)
                val avail = (prog.totalLength - progPos).coerceAtLeast(0L)
                progRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    minOf(dataSpec.length, avail)
                } else {
                    avail
                }
                return progRemaining
            }
            activeMem = null
            activeProg = null
            return httpDelegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            activeMem?.let { return it.read(buffer, offset, length) }
            val prog = activeProg
            if (prog != null) {
                if (length == 0) return 0
                if (progRemaining <= 0L) return C.RESULT_END_OF_INPUT
                val toRead = minOf(length.toLong(), progRemaining).toInt()
                val read = prog.readAt(progPos, buffer, offset, toRead)
                if (read <= 0) return C.RESULT_END_OF_INPUT
                progPos += read
                progRemaining -= read
                return read
            }
            return httpDelegate.read(buffer, offset, length)
        }

        override fun getUri() = activeMem?.uri ?: progUri ?: httpDelegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            if (activeMem != null || activeProg != null) emptyMap() else httpDelegate.responseHeaders

        override fun close() {
            activeMem?.close()
            activeMem = null
            activeProg = null
            progUri = null
            httpDelegate.close()
        }
    }
}
