package org.seqra.ir.impl.storage.ers.kv

import org.seqra.ir.api.storage.ByteArrayKey
import org.seqra.ir.api.storage.kv.Cursor

internal class TypeIdWithName(val typeId: Int, val name: String) {

    override fun equals(other: Any?): Boolean {
        return other is TypeIdWithName && typeId == other.typeId && name == other.name
    }

    override fun hashCode(): Int {
        return name.hashCode() * 31 + typeId
    }

    override fun toString(): String {
        return "TypeIdWithName(typeId=$typeId, name='$name')"
    }
}

internal infix fun Int.with(name: String) = TypeIdWithName(this, name)

internal fun Cursor.asReversedIterable(maxKey: ByteArray): Iterable<Pair<ByteArray, ByteArray>> {
    val maxKeyComparable = ByteArrayKey(maxKey)
    do {
        if (!movePrev()) return emptyList()
    } while (ByteArrayKey(key) > maxKeyComparable)
    return Iterable {
        object : Iterator<Pair<ByteArray, ByteArray>> {

            private var skipMove = true

            override fun hasNext() = (skipMove || movePrev()).also { skipMove = false }
            override fun next() = key to value
        }
    }
}