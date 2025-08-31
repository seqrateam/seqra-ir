package org.seqra.ir.impl.caches.xodus

import jetbrains.exodus.core.dataStructures.ConcurrentObjectCache
import jetbrains.exodus.core.dataStructures.ObjectCacheBase
import jetbrains.exodus.core.dataStructures.SoftConcurrentObjectCache
import org.seqra.ir.api.caches.PluggableCache
import org.seqra.ir.api.caches.PluggableCacheBuilder
import org.seqra.ir.api.caches.PluggableCacheException
import org.seqra.ir.api.caches.PluggableCacheProvider
import org.seqra.ir.api.caches.PluggableCacheStats
import org.seqra.ir.api.caches.ValueStoreType

const val XODUS_CACHE_PROVIDER_ID = "org.seqra.ir.impl.caches.xodus.XodusCacheProvider"

class XodusCacheProvider : PluggableCacheProvider {

    override val id = XODUS_CACHE_PROVIDER_ID

    override fun <K : Any, V : Any> newBuilder(): PluggableCacheBuilder<K, V> = XodusCacheBuilder()
}

private class XodusCacheBuilder<K : Any, V : Any> : PluggableCacheBuilder<K, V>() {
    override fun build(): PluggableCache<K, V> {
        if (valueRefType == ValueStoreType.WEAK) {
            throw PluggableCacheException("XodusCache doesn't support weak references to values")
        }
        return XodusCache(
            if (valueRefType == ValueStoreType.SOFT) {
                SoftConcurrentObjectCache(maximumSize)
            } else {
                ConcurrentObjectCache(maximumSize)
            }
        )
    }
}

/**
 * Generally, Xodus' [jetbrains.exodus.core.dataStructures.ObjectCacheBase] is not synchronized, but [XodusCacheBuilder] creates
 * its "concurrent" implementations which do not require synchronization. If this ever changes,
 * [XodusCache] should be synchronized.
 */
private class XodusCache<K : Any, V : Any>(private val cache: ObjectCacheBase<K, V>) : PluggableCache<K, V> {

    override fun get(key: K): V? = cache.tryKey(key)

    override fun set(key: K, value: V) {
        cache.cacheObject(key, value)
    }

    override fun remove(key: K) {
        cache.remove(key)
    }

    override fun getStats(): PluggableCacheStats = object : PluggableCacheStats {

        override val hitRate: Double = cache.hitRate().toDouble()

        override val requestCount: Long = cache.attempts.toLong()
    }
}