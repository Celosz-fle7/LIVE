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

/**
 * Store in-memory + Media3 DataSource.Factory untuk stream terdekripsi (skema `mem://`).
 * Menghilangkan kebutuhan ServerSocket / localhost HTTP proxy sepenuhnya.
 */
@UnstableApi
object InMemoryMediaStore {
    private val cache = ConcurrentHashMap<String, ByteArray>()
    private val insertionOrder = ArrayDeque<String>()

    @Synchronized
    fun put(key: String, data: ByteArray) {
        if (!cache.containsKey(key)) {
            if (insertionOrder.size >= 6) {
                val oldest = insertionOrder.removeFirst()
                cache.remove(oldest)
            }
            insertionOrder.addLast(key)
        }
        cache[key] = data
    }

    fun get(key: String): ByteArray? = cache[key]

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
        private val listeners = mutableListOf<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners.add(transferListener)
            httpDelegate.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            if (dataSpec.uri.scheme == "mem") {
                val key = dataSpec.uri.host.orEmpty() + dataSpec.uri.path.orEmpty()
                val cleanKey = key.trimStart('/')
                val bytes = cache[cleanKey] ?: cache[dataSpec.uri.lastPathSegment.orEmpty()]
                    ?: throw IOException("InMemoryMediaStore miss for $cleanKey")
                val ds = ByteArrayDataSource(bytes)
                listeners.forEach { ds.addTransferListener(it) }
                activeMem = ds
                return ds.open(dataSpec)
            }
            activeMem = null
            return httpDelegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val mem = activeMem
            return mem?.read(buffer, offset, length) ?: httpDelegate.read(buffer, offset, length)
        }

        override fun getUri() = activeMem?.uri ?: httpDelegate.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            if (activeMem != null) emptyMap() else httpDelegate.responseHeaders

        override fun close() {
            activeMem?.close()
            activeMem = null
            httpDelegate.close()
        }
    }
}
