package org.seqra.ir.testing.types

import org.seqra.ir.api.jvm.JIRClassType
import org.seqra.ir.api.jvm.JIRTypeVariable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.Closeable


class InnerTypesTest : BaseTypesTest() {

    @Test
    fun `inner classes linked to method`() {
        val classWithInners = findType<InnerClasses<*>>()
        val inners = classWithInners.innerTypes
        assertEquals(4, inners.size)
        val methodLinked = inners.first { it.typeName == "org.seqra.ir.testing.types.InnerClasses<W>\$1" }
        with(methodLinked.fields) {
            with(first { it.name == "stateT" }) {
                assertEquals("T", (type as JIRTypeVariable).symbol)
            }
            with(first { it.name == "stateW" }) {
                assertEquals("W", type.typeName)
            }
        }
    }

    @Test
    fun `inner classes type name should be right`() {
        val classWithInners = findType<InnerClasses<*>>()
        val inners = classWithInners.innerTypes
        assertEquals(listOf(
            "org.seqra.ir.testing.types.InnerClasses<W>.InnerStateOverriden<W>",
            "org.seqra.ir.testing.types.InnerClasses<W>.InnerState",
            "org.seqra.ir.testing.types.InnerClasses<W>$2",
            "org.seqra.ir.testing.types.InnerClasses<W>$1"
        ), inners.map { it.typeName }.sortedDescending())
    }

    @Test
    fun `get not parameterized inner types`() {
        val innerClasses = findType<InnerClasses<*>>().innerTypes
        assertEquals(4, innerClasses.size)
        with(innerClasses.first { it.typeName == "org.seqra.ir.testing.types.InnerClasses<W>.InnerState" }) {
            val fields = fields
            assertEquals(2, fields.size)

            with(fields.first { it.name == "stateW" }) {
                with(type.assertIs<JIRTypeVariable>()) {
                    assertEquals("W", symbol)
                }
            }
        }
    }

    @Test
    fun `get inner type linked to parameterized method`() {
        val innerClasses = findType<InnerClasses<*>>().innerTypes
        assertEquals(4, innerClasses.size)
        with(innerClasses.first { it.typeName.contains("1") }) {
            val fields = fields
            assertEquals(4, fields.size)
            interfaces.first().assertClassType<Runnable>()

            with(fields.first { it.name == "stateT" }) {
                assertEquals("stateT", name)
                with(type.assertIs<JIRTypeVariable>()) {
                    assertEquals("T", symbol)
                }
            }
            with(fields.first { it.name == "stateW" }) {
                assertEquals("stateW", name)
                with(type.assertIs<JIRTypeVariable>()) {
                    assertEquals("W", symbol)
                }
            }

        }
    }

    @Test
    fun `parameterized inner type with parent type parameterization`() {
        with(field("stateString")) {
            fields.first { it.name == "stateW" }.type.assertClassType<String>()
        }

    }

    @Test
    fun `custom parameterization of method overrides outer class parameterization`() {
        with(field("stateString")) {
            with(methods.first { it.name == "method" }) {
                with(returnType.assertIs<JIRTypeVariable>()) {
                    assertEquals("W", symbol)
                    assertEquals("java.util.List<java.io.Closeable>", bounds.first().typeName)
                }
            }
        }

    }

    @Test
    fun `custom parameterization of inner type overrides outer class parameterization`() {

        with(field("stateClosable")) {
            with(fields.first { it.name == "stateW" }) {
                type.assertClassType<Closeable>()
            }
            with(methods.first { it.name == "method" }) {
                with(returnType.assertIs<JIRTypeVariable>()) {
                    assertEquals("W", symbol)
                    assertEquals("java.util.List<java.lang.Integer>", bounds.first().typeName)
                }
            }
        }

    }

    private fun field(fieldName: String): JIRClassType {
        return findType<InnerClasses<*>>().fields.first {
            it.name == fieldName
        }.type.assertIsClass()
    }

}
