package org.seqra.ir.testing.storage.ers

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.seqra.ir.impl.storage.ers.getBinding
import java.nio.charset.StandardCharsets

class BindingsTest {

    @Test
    fun ordinaryStrings() {
        assertEquals("username", serializeDeserialize("username"))
    }

    @Test()
    @Disabled
    fun weirdChars() {
        val s = String(hash, StandardCharsets.UTF_8)
        assertArrayEquals(serializedHash, getBinding(s).getBytes(s))
        assertEquals(s, serializeDeserialize(s))
    }

    @Test
    fun intBinding() {
        assertEquals(0, serializeDeserialize(0))
        assertEquals(1, serializeDeserialize(1))
        assertEquals(-1, serializeDeserialize(-1))
        assertEquals(Int.MAX_VALUE, serializeDeserialize(Int.MAX_VALUE))
        assertEquals(Int.MIN_VALUE, serializeDeserialize(Int.MIN_VALUE))
    }

    @Test
    fun longBinding() {
        assertEquals(0L, serializeDeserialize(0L))
        assertEquals(1L, serializeDeserialize(1L))
        assertEquals(-1L, serializeDeserialize(-1L))
        assertEquals(Long.MAX_VALUE, serializeDeserialize(Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, serializeDeserialize(Long.MIN_VALUE))
    }

    @Test
    fun longBindingCompressed() {
        assertThrows<IllegalArgumentException> { serializeDeserializeCompressed(-1L) }
        assertEquals(0L, serializeDeserializeCompressed(0L))
        assertEquals(Long.MAX_VALUE, serializeDeserializeCompressed(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE / 2, serializeDeserializeCompressed(Long.MAX_VALUE / 2))
        assertEquals(Long.MAX_VALUE shr 17, serializeDeserializeCompressed(Long.MAX_VALUE shr 17))
    }

    @Test
    fun booleanBinding() {
        assertEquals(false, serializeDeserialize(false))
        assertEquals(true, serializeDeserialize(true))
    }

    @Test
    fun doubleBinding() {
        assertEquals(.0, serializeDeserialize(.0))
        assertEquals(1.0, serializeDeserialize(1.0))
        assertEquals(2.71281828, serializeDeserialize(2.71281828))
        assertEquals(3.14159265259, serializeDeserialize(3.14159265259))
        assertEquals(Double.NEGATIVE_INFINITY, serializeDeserialize(Double.NEGATIVE_INFINITY))
        assertEquals(Double.POSITIVE_INFINITY, serializeDeserialize(Double.POSITIVE_INFINITY))
        assertEquals(Double.MIN_VALUE, serializeDeserialize(Double.MIN_VALUE))
        assertEquals(Double.MAX_VALUE, serializeDeserialize(Double.MAX_VALUE))
        assertEquals(java.lang.Double.MIN_NORMAL, serializeDeserialize(java.lang.Double.MIN_NORMAL))
        assertEquals(Double.NaN, serializeDeserialize(Double.NaN))
    }

    private companion object {
        val hash = byteArrayOf(
            -17, -65, -67, 68, 85, 96, -17, -65, -67, 28, 4, 76, 28,
            -17, -65, -67, -17, -65, -67, 90, 17, -17, -65, -67, 0, 95
        )
        val serializedHash = byteArrayOf(
            -17, -65, -67, 68, 85, 96, -17, -65, -67, 28, 4, 76, 28,
            -17, -65, -67, -17, -65, -67, 90, 17, -17, -65, -67, -64, -128, 95
        )

        private fun serializeDeserialize(s: Any): Any =
            getBinding(s).let { binding ->
                binding.getObject(binding.getBytes(s))
            }

        private fun serializeDeserializeCompressed(s: Any): Any =
            getBinding(s).let { binding ->
                binding.getObjectCompressed(binding.getBytesCompressed(s))
            }
    }
}
