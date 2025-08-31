package org.seqra.ir.testing.types

import kotlinx.coroutines.runBlocking
import org.seqra.ir.api.jvm.JIRClassType
import org.seqra.ir.api.jvm.JIRTypeVariable
import org.seqra.ir.api.jvm.JIRTypedField
import org.seqra.ir.api.jvm.JIRTypedMethod
import org.seqra.ir.testing.types.MultipleParametrization.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.reflect.KFunction2
import kotlin.reflect.KMutableProperty1

class MultiParametersTest : BaseTypesTest() {

    private val finalW = "java.util.ArrayList<java.lang.String>"
    private val finalZ = "java.util.ArrayList<java.util.ArrayList<java.lang.String>>"

    @Test
    fun `first level of parameterization fields`() {
        runBlocking {
            val test1 = findType<SuperTest1<*, *, *>>()
            with(test1.field(SuperTest1<*, *, *>::stateT)) {
                assertEquals("T", (type as JIRTypeVariable).symbol)
            }
            with(test1.field(SuperTest1<*, *, *>::stateW)) {
                assertEquals("W", (type as JIRTypeVariable).symbol)
            }
            with(test1.field(SuperTest1<*, *, *>::stateZ)) {
                assertEquals("Z", (type as JIRTypeVariable).symbol)
            }
        }
    }

    @Test
    fun `second level of parameterization fields`() {
        runBlocking {
            val test2 = findType<SuperTest2<*, *>>()
            with(test2.field(SuperTest1<*, *, *>::stateT)) {
                type.assertClassType<String>()
            }
            with(test2.field(SuperTest1<*, *, *>::stateW)) {
                val variable = type as JIRTypeVariable
                assertEquals("W", variable.symbol)
            }
            with(test2.field(SuperTest1<*, *, *>::stateZ)) {
                assertEquals("Z", (type as JIRTypeVariable).symbol)
            }
        }
    }

    @Test
    fun `third level of parameterization fields`() {
        runBlocking {
            val test2 = findType<SuperTest3>()
            with(test2.field(SuperTest1<*, *, *>::stateT)) {
                type.assertClassType<String>()
            }
            with(test2.field(SuperTest1<*, *, *>::stateW)) {
                val variable = type
                assertEquals(finalW, variable.typeName)
            }
            with(test2.field(SuperTest1<*, *, *>::stateZ)) {
                val variable = type
                assertEquals(finalZ, variable.typeName)
            }
        }
    }

    @Test
    fun `first level of parameterization methods`() {
        runBlocking {
            val test1 = findType<SuperTest1<*, *, *>>()
            with(test1.method(SuperTest1<*, *, *>::runT)) {
                assertEquals("T", (returnType as JIRTypeVariable).symbol)
                assertEquals("T", (parameters.first().type as JIRTypeVariable).symbol)
            }
            with(test1.method(SuperTest1<*, *, *>::runW)) {
                assertEquals("W", (returnType as JIRTypeVariable).symbol)
                assertEquals("W", (parameters.first().type as JIRTypeVariable).symbol)
            }
            with(test1.method(SuperTest1<*, *, *>::runZ)) {
                assertEquals("Z", (returnType as JIRTypeVariable).symbol)
                assertEquals("Z", (parameters.first().type as JIRTypeVariable).symbol)
            }
        }
    }

    @Test
    fun `second level of parameterization methods`() {
        runBlocking {
            val test2 = findType<SuperTest2<*, *>>()
            with(test2.method(SuperTest1<*, *, *>::runT)) {
                parameters.first().type.assertClassType<String>()
                returnType.assertClassType<String>()
            }
            with(test2.method(SuperTest1<*, *, *>::runW)) {
                assertEquals("W", (returnType as JIRTypeVariable).symbol)
                assertEquals("W", (parameters.first().type as JIRTypeVariable).symbol)
            }
            with(test2.method(SuperTest1<*, *, *>::runZ)) {
                assertEquals("Z", (returnType as JIRTypeVariable).symbol)
                assertEquals("Z", (parameters.first().type as JIRTypeVariable).symbol)
            }
        }
    }

    @Test
    fun `third level of parameterization methods`() {
        runBlocking {
            val test2 = findType<SuperTest3>()
            with(test2.method(SuperTest1<*, *, *>::runT)) {
                parameters.first().type.assertClassType<String>()
                returnType.assertClassType<String>()
            }
            with(test2.method(SuperTest1<*, *, *>::runW)) {
                assertEquals(finalW, parameters.first().type.typeName)
                assertEquals(finalW, returnType.typeName)
            }
            with(test2.method(SuperTest1<*, *, *>::runZ)) {
                assertEquals(finalZ, parameters.first().type.typeName)
                assertEquals(finalZ, returnType.typeName)
            }
        }
    }

    private suspend fun JIRClassType.field(prop: KMutableProperty1<SuperTest1<*, *, *>, *>): JIRTypedField {
        return fields.first { it.name == prop.name }
    }

    private suspend fun JIRClassType.method(prop: KFunction2<SuperTest1<*, *, *>, Nothing, *>): JIRTypedMethod {
        return methods.first { it.name == prop.name }
    }

}
