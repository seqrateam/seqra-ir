package org.seqra.ir.impl.storage.ers.ram

import org.seqra.ir.api.storage.ByteArrayKey
import org.seqra.ir.api.storage.asComparable
import org.seqra.ir.api.storage.ers.Entity
import org.seqra.ir.api.storage.ers.EntityId
import org.seqra.ir.api.storage.ers.EntityIterable
import org.seqra.ir.util.io.writeByteBuffer
import org.seqra.ir.util.io.writeVlqUnsigned
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.math.min

internal open class AttributesImmutable(
    values: ByteBuffer,
    instanceIds: List<Long>,
    private val offsetsAndLens: ByteBuffer,
    sortedByValue: Boolean? = null,
) {

    private val values = values.asReadOnlyBuffer()
    private val instanceIdCollection = instanceIds.toInstanceIdCollection(sorted = true)
    private val offsetsAndLensLongs = offsetsAndLens.asLongBuffer().asReadOnlyBuffer()
    private val sortedByValue: Boolean // `true` if order of instance ids is the same as the one sorted by value
    private val sortedByValueInstanceIds by lazy {
        // NB!
        // We need stable sorting here, and java.util.Collections.sort() guarantees the sort is stable
        instanceIdCollection.asIterable()
            .sortedBy { get(it)!!.asComparable() }.toInstanceIdCollection(sorted = false)
    }

    init {
        if (sortedByValue != null) {
            this.sortedByValue = sortedByValue
        } else {
            var sorted = true
            var prevId = Long.MIN_VALUE
            var prevValue: ByteArrayKey? = null
            for (i in instanceIds.indices) {
                // check if instanceIds are sorted in ascending order and there are no duplicates
                val currentId = instanceIds[i]
                if (prevId >= currentId) {
                    error("AttributesImmutable: instanceIds should be sorted and have no duplicates")
                }
                prevId = currentId
                // check if order of values is the same as order of ids
                if (sorted) {
                    val currentValue = ByteArrayKey(getByIndex(i))
                    prevValue?.let {
                        if (it > currentValue) {
                            sorted = false
                        }
                    }
                    prevValue = currentValue
                }
            }
            this.sortedByValue = sorted
        }
    }

    operator fun get(instanceId: Long): ByteArray? {
        val index = instanceIdCollection.getIndex(instanceId)
        if (index < 0) {
            return null
        }
        return getByIndex(index)
    }

    fun navigate(value: ByteArray, leftBound: Boolean): AttributesCursor {
        if (instanceIdCollection.isEmpty) {
            return EmptyAttributesCursor
        }
        val ids = if (sortedByValue) instanceIdCollection else sortedByValueInstanceIds
        val valueComparable by lazy(LazyThreadSafetyMode.NONE) { value.asComparable() }
        // in order to find exact left or right bound, we have to use binary search without early break on equality
        var low = 0
        var high = ids.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val cmp = if (sortedByValue) {
                val offsetAndLen = offsetsAndLensLongs[mid]
                val offset = offsetAndLen.toInt()
                val len = (offsetAndLen shr 32).toInt()
                -compareValueTo(offset, len, value)
            } else {
                valueComparable.compareTo(get(ids[mid])!!.asComparable())
            }
            if (cmp == 0) {
                found = mid
            }
            if (leftBound) {
                if (cmp > 0) {
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            } else {
                if (cmp < 0) {
                    high = mid - 1
                } else {
                    low = mid + 1
                }
            }
        }
        val index = if (found in 0 until ids.size) found else -(low + 1)
        return object : AttributesCursor {

            private var idx: Int = if (index < 0) -index - 1 else index

            override val hasMatch: Boolean = index >= 0

            override val current: Pair<Long, ByteArray>
                get() {
                    val instanceId = ids[idx]
                    return instanceId to if (sortedByValue) getByIndex(idx) else get(instanceId)!!
                }

            override fun moveNext(): Boolean = ++idx < ids.size

            override fun movePrev(): Boolean = --idx >= 0
        }
    }

    fun dump(output: OutputStream) {
        output.write(if (sortedByValue) 1 else 0)
        output.writeByteBuffer(values)
        instanceIdCollection.dump(output)
        // save offsets and lens
        output.writeByteBuffer(offsetsAndLens)
    }

    private fun getByIndex(index: Int): ByteArray {
        val offsetAndLen = offsetsAndLensLongs[index]
        val offset = offsetAndLen.toInt()
        val len = (offsetAndLen shr 32).toInt()
        return ByteArray(len).also { values.sliceBuffer(offset, len).get(it) }
    }

    /**
     * Compare a value from values array identified by offset in the array and length of the value
     */
    private fun compareValueTo(offset: Int, len: Int, other: ByteArray): Int {
        for (i in 0 until min(len, other.size)) {
            val cmp = (values[offset + i].toInt() and 0xff).compareTo(other[i].toInt() and 0xff)
            if (cmp != 0) return cmp
        }
        return len - other.size
    }
}

internal interface AttributesCursor {

    val hasMatch: Boolean

    val current: Pair<Long, ByteArray>

    fun moveNext(): Boolean

    fun movePrev(): Boolean
}

private object EmptyAttributesCursor : AttributesCursor {
    override val hasMatch: Boolean = false
    override val current: Pair<Long, ByteArray> = error("EmptyAttributesCursor doesn't navigate")
    override fun moveNext(): Boolean = false
    override fun movePrev(): Boolean = false
}

internal class AttributesCursorEntityIterable(
    private val txn: RAMTransaction,
    private val typeId: Int,
    private val cursor: AttributesCursor,
    private val forwardDirection: Boolean,
    private val filter: ((Long, ByteArray) -> Boolean)? = null
) : EntityIterable {

    override fun iterator(): Iterator<Entity> = object : Iterator<Entity> {

        private var next: Entity? = null

        override fun hasNext(): Boolean {
            if (next == null) {
                next = advance()
            }
            return next != null
        }

        override fun next(): Entity {
            if (next == null) {
                next = advance()
            }
            return next.also { next = null } ?: throw NoSuchElementException()
        }

        private fun advance(): Entity? {
            if (next == null) {
                val moved = if (forwardDirection) cursor.moveNext() else cursor.movePrev()
                if (moved) {
                    val (instanceId, value) = cursor.current
                    filter?.let { predicate ->
                        if (!predicate(instanceId, value)) {
                            return null
                        }
                    }
                    next = txn.getEntityOrNull(EntityId(typeId, instanceId))
                }
            }
            return next
        }
    }
}

// Collection of instanceIds
private interface InstanceIdCollection {
    val isEmpty: Boolean get() = size == 0
    val size: Int
    operator fun get(index: Int): Long
    fun getIndex(instanceId: Long): Int
    fun dump(output: OutputStream)
}

private fun List<Long>.toInstanceIdCollection(sorted: Boolean): InstanceIdCollection {
    if (isEmpty()) {
        error("InstanceIdCollection should not be empty")
    }
    if (sorted && this[0] == 0L && this[size - 1] == (size - 1).toLong()) {
        return LongRangeInstanceIdCollection(0L until size)
    }
    return if (sorted) {
        SortedListInstanceIdCollection(this)
    } else {
        UnsortedListInstanceIdCollection(this)
    }
}

// InstanceIdCollection wrapping unsorted list of unsigned longs
private class UnsortedListInstanceIdCollection(val instances: List<Long>) : InstanceIdCollection {
    override val size: Int get() = instances.size
    override fun get(index: Int): Long = instances[index]
    override fun getIndex(instanceId: Long): Int = instances.indexOf(instanceId)
    override fun dump(output: OutputStream) =
        error("UnsortedListInstanceIdCollection.dump() should never be invoked")
}

// InstanceIdCollection wrapping sorted list of unsigned longs
private class SortedListInstanceIdCollection(val instances: List<Long>) : InstanceIdCollection {
    override val size: Int get() = instances.size
    override fun get(index: Int): Long = instances[index]
    override fun getIndex(instanceId: Long): Int = instances.binarySearch(instanceId)
    override fun dump(output: OutputStream) {
        if (instances.last().isInt()) {
            output.write(0) // mark implementation having all ints
            output.writeByteBuffer(
                ByteBuffer.allocate(instances.size * Int.SIZE_BYTES).apply {
                    instances.forEach { instanceId ->
                        putInt(instanceId.toInt())
                    }
                }.flip() as ByteBuffer
            )
        } else {
            output.write(1) // mark implementation having all longs
            output.writeByteBuffer(
                ByteBuffer.allocate(instances.size * Long.SIZE_BYTES).apply {
                    instances.forEach { instanceId ->
                        putLong(instanceId)
                    }
                }.flip() as ByteBuffer
            )
        }
    }
}

// InstanceIdCollection wrapping LongRange which is growing progression with step 1
private class LongRangeInstanceIdCollection(val range: LongRange) : InstanceIdCollection {
    override val size: Int get() = (range.last - range.first).toInt() + 1
    override fun get(index: Int): Long = range.first + index
    override fun getIndex(instanceId: Long): Int = (instanceId - range.first).toInt()
    override fun dump(output: OutputStream) {
        output.write(2) // mark LongRangeInstanceIdCollection implementation
        output.writeVlqUnsigned(range.first)
        output.writeVlqUnsigned(range.last - range.first)
    }
}

private fun Long.isInt() = this in 0L..Int.MAX_VALUE

private fun InstanceIdCollection.asIterable(): Iterable<Long> {
    return when (this) {
        is LongRangeInstanceIdCollection -> range
        is SortedListInstanceIdCollection -> instances.asIterable()
        is UnsortedListInstanceIdCollection -> instances.asIterable()
        else -> error("Unknown InstanceIdCollection class: $javaClass")
    }
}

private fun ByteBuffer.sliceBuffer(offset: Int, length: Int): ByteBuffer {
    return duplicate().position(offset).limit(offset + length) as ByteBuffer
}