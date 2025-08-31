package org.seqra.ir.testing.storage.ers

import org.seqra.ir.impl.storage.ers.ram.CompactPersistentLongSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompactPersistentLongSetTest {

    @Test
    fun `add remove`() {
        var set = CompactPersistentLongSet()
        set = set.add(1L)
        set = set.add(2L)
        set = set.add(3L)
        assertEquals(set.size, 3)
        assertEquals(setOf(1L, 2L, 3L), set.toSet())
        set = set.remove(2L)
        assertEquals(set.size, 2)
        assertEquals(setOf(1L, 3L), set.toSet())
        set = set.remove(3L)
        assertEquals(set.size, 1)
        assertEquals(setOf(1L), set.toSet())
        set = set.remove(1L)
        assertTrue(set.isEmpty())
    }
}