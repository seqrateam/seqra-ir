package org.seqra.ir.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import java.lang.ref.SoftReference
import java.lang.ref.WeakReference
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

val BACKGROUND_PARALLELISM
    get() = Integer.getInteger(
        "org.seqra.ir.background.parallelism",
        64.coerceAtLeast(Runtime.getRuntime().availableProcessors())
    )

class BackgroundScope : CoroutineScope {

    @OptIn(ExperimentalCoroutinesApi::class)
    override val coroutineContext = Dispatchers.IO.limitedParallelism(BACKGROUND_PARALLELISM) + SupervisorJob()
}

fun <W, T> softLazy(getter: () -> T): ReadOnlyProperty<W, T> {
    return object : ReadOnlyProperty<W, T> {
        @Volatile
        var softRef: SoftReference<T>? = null

        override fun getValue(thisRef: W, property: KProperty<*>): T {
            return softRef?.get() ?: getter().also { softRef = SoftReference(it) }
        }
    }
}

fun <W, T> weakLazy(getter: () -> T): ReadOnlyProperty<W, T> {
    return object : ReadOnlyProperty<W, T> {
        @Volatile
        var weakRef: WeakReference<T>? = null

        override fun getValue(thisRef: W, property: KProperty<*>): T {
            return weakRef?.get() ?: getter().also { weakRef = WeakReference(it) }
        }
    }
}