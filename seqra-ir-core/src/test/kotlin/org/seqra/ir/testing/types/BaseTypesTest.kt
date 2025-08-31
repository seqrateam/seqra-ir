package org.seqra.ir.testing.types

import org.seqra.ir.api.jvm.JIRClassType
import org.seqra.ir.api.jvm.JIRType
import org.seqra.ir.testing.BaseTest
import org.seqra.ir.testing.WithGlobalDbImmutable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull

abstract class BaseTypesTest : BaseTest() {

    companion object : WithGlobalDbImmutable()

    protected inline fun <reified T> findType(): JIRClassType {
        val found = cp.findTypeOrNull(T::class.java.name)
        assertNotNull(found)
        return found!!.assertIs()
    }

    protected fun JIRType?.assertIsClass(): JIRClassType {
        assertNotNull(this)
        return this!!.assertIs()
    }

    protected inline fun <reified T> JIRType?.assertClassType(): JIRClassType {
        val expected = findType<T>()
        assertEquals(
            expected.jIRClass.name,
            (this as? JIRClassType)?.jIRClass?.name,
            "Expected ${expected.jIRClass.name} but got ${this?.typeName}"
        )
        return this as JIRClassType
    }


    protected inline fun <reified T> Any.assertIs(): T {
        return assertInstanceOf(T::class.java, this)
    }
}
