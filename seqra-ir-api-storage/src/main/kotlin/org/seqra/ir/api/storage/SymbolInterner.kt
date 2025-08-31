package org.seqra.ir.api.storage

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

interface SymbolInterner : Closeable {
    fun findOrNew(symbol: String): Long
    fun findSymbolName(symbolId: Long): String?
    fun flush(context: org.seqra.ir.api.storage.StorageContext, force: Boolean = false)
}

abstract class ConcurrentSymbolInterner : org.seqra.ir.api.storage.SymbolInterner {

    protected val symbolsIdGen = AtomicLong()
    protected val symbolsCache = ConcurrentHashMap<String, Long>()
    protected val idCache = ConcurrentHashMap<Long, String>()
    protected val newElements = ConcurrentSkipListMap<String, Long>()

    override fun findOrNew(symbol: String): Long {
        return symbolsCache.computeIfAbsent(symbol) {
            symbolsIdGen.incrementAndGet().also {
                newElements[symbol] = it
                idCache[it] = symbol
            }
        }
    }

    override fun findSymbolName(symbolId: Long): String? = idCache[symbolId]

    override fun close() {
        symbolsCache.clear()
        newElements.clear()
    }
}

fun String.asSymbolId(symbolInterner: org.seqra.ir.api.storage.SymbolInterner): Long {
    return symbolInterner.findOrNew(this)
}