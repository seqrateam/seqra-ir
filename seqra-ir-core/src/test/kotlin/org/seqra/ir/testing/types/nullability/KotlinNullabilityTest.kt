package org.seqra.ir.testing.types.nullability

import kotlinx.coroutines.runBlocking
import org.seqra.ir.api.jvm.JIRClassType
import org.seqra.ir.testing.KotlinNullabilityExamples
import org.seqra.ir.testing.types.BaseTypesTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KotlinNullabilityTest : BaseTypesTest() {
    @Test
    fun `nullability for simple generics`() = runBlocking {
        val clazz = findType<KotlinNullabilityExamples>()
        val params = clazz.declaredMethods.single { it.name == "simpleGenerics" }.parameters
        val actualNullability = params.map { it.type.nullabilityTree }
        val expectedNullability = listOf(
            // SomeContainer<SomeContainer<Int>>
            buildTree(false) {
                +buildTree(false) {
                    +buildTree(false)
                }
            },

            // SomeContainer<SomeContainer<Int?>>
            buildTree(false) {
                +buildTree(false) {
                    +buildTree(true)
                }
            },

            // SomeContainer<SomeContainer<Int>?>
            buildTree(false) {
                +buildTree(true) {
                    +buildTree(false)
                }
            }
        )

        assertEquals(expectedNullability, actualNullability)
    }

    @Test
    fun `nullability for extension function`() = runBlocking {
        val clazz = findType<KotlinNullabilityExamples>()
        val actualNullability = clazz.declaredMethods.single { it.name == "extensionFunction" }
            .parameters.single()
            .type
            .nullabilityTree

        // SomeContainer<SomeContainer<Int?>?>
        val expectedNullability = buildTree(false) {
            +buildTree(true) {
                +buildTree(true)
            }
        }

        assertEquals(expectedNullability, actualNullability)
    }

    @Test
    fun `nullability for generics with projections`() = runBlocking {
        val clazz = findType<KotlinNullabilityExamples>()
        val params = clazz.declaredMethods.single { it.name == "genericsWithProjection" }.parameters
        val actualNullability = params.map { it.type.nullabilityTree }
        val expectedNullability = listOf(
            // SomeContainer<out String?>
            buildTree(false) {
                +buildTree(true)
            },

            // SomeContainer<in String>
            buildTree(false) {
                +buildTree(false)
            },

            // SomeContainer<*>
            buildTree(false) {
                +buildTree(true)
            }
        )

        assertEquals(expectedNullability, actualNullability)
    }

    @Test
    fun `nullability for arrays`() = runBlocking {
        val clazz = findType<KotlinNullabilityExamples>()
        val params = clazz.declaredMethods.single { it.name == "javaArrays" }.parameters
        val actualNullability = params.map { it.type.nullabilityTree }
        val expectedNullability = listOf(
            // IntArray?
            buildTree(true) {
                +buildTree(false)
            },

            // Array<KotlinNullabilityExamples.SomeContainer<Int>>
            buildTree(false) {
                +buildTree(false) {
                    +buildTree(false)
                }
            }
        )

        assertEquals(expectedNullability, actualNullability)
    }

    @Test
    fun `nullability on type parameters`() = runBlocking {
        val clazz = findType<KotlinNullabilityExamples>()
        val params = clazz.declaredMethods.single { it.name == "typeVariableParameters" }.parameters
        val actualNullability = params.map { it.type.nullable }
        val expectedNullability = listOf(false, true) // T, T?

        assertEquals(expectedNullability, actualNullability)
    }

    @Test
    fun `nullability on type variable declarations`() = runBlocking {
        val clazz = findType<KotlinNullabilityExamples>()
        val params = clazz.declaredMethods.single { it.name == "typeVariableDeclarations" }.typeParameters
        val actualNullability = params.map { it.bounds.single().nullabilityTree }

        val expectedNullability = listOf(
            // List<Int?>
            buildTree(false) {
                +buildTree(true)
            },

            // List<Int>?
            buildTree(true) {
                +buildTree(false)
            },
        )

        assertEquals(expectedNullability, actualNullability)
    }

    @Test
    fun `nullability after substitution with notNull type`() = runBlocking {
        val clazz = findType<KotlinNullabilityExamples>()
        val field = clazz.declaredFields.single { it.name == "containerOfNotNull" }

        val fieldsNullability = (field.type as JIRClassType)
            .fields
            .sortedBy { it.name }
            .map { it.type.nullabilityTree }

        // E -> String
        val expectedNullability = listOf(
            // List<E>
            buildTree(false) {
                +buildTree(false)
            },

            // List<E?>
            buildTree(false) {
                +buildTree(true)
            },

            // E
            buildTree(false),

            // E?
            buildTree(true)
        )

        assertEquals(expectedNullability, fieldsNullability)
    }

    @Test
    fun `nullability after substitution with nullable type`() = runBlocking {
        val clazz = findType<KotlinNullabilityExamples>()
        val field = clazz.declaredFields.single { it.name == "containerOfNullable" }

        val fieldsNullability = (field.type as JIRClassType)
            .fields
            .sortedBy { it.name }
            .map { it.type.nullabilityTree }

        // E -> String?
        val expectedNullability = listOf(
            // List<E>
            buildTree(false) {
                +buildTree(true)
            },

            // List<E?>
            buildTree(false) {
                +buildTree(true)
            },

            // E
            buildTree(true),

            // E?
            buildTree(true)
        )

        assertEquals(expectedNullability, fieldsNullability)
    }

    @Test
    fun `nullability after passing nullable type through chain of notnull type variables`() = runBlocking {
        val clazz = findType<KotlinNullabilityExamples>()
        val fieldType = clazz.declaredFields.single { it.name == "someContainerProducer" }.type
        val innerMethodType =
            (fieldType as JIRClassType).declaredMethods.single { it.name == "produceContainer" }.returnType

        val fieldsNullability = (innerMethodType as JIRClassType)
            .fields
            .sortedBy { it.name }
            .map { it.type.nullabilityTree }

        // P -> Int?, E -> P
        val expectedNullability = listOf(
            // List<E>
            buildTree(false) {
                +buildTree(true)
            },

            // List<E?>
            buildTree(false) {
                +buildTree(true)
            },

            // E
            buildTree(true),

            // E?
            buildTree(true)
        )

        assertEquals(expectedNullability, fieldsNullability)
    }
}
