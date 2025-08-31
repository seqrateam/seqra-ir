package org.seqra.ir.testing.features

import kotlinx.coroutines.runBlocking
import org.seqra.ir.api.jvm.FieldUsageMode
import org.seqra.ir.api.jvm.ext.CONSTRUCTOR
import org.seqra.ir.api.jvm.ext.findClass
import org.seqra.ir.impl.features.usagesExt
import org.seqra.ir.testing.BaseTest
import org.seqra.ir.testing.WithGlobalDb
import org.seqra.ir.testing.WithGlobalSQLiteDb
import org.seqra.ir.testing.usages.fields.FieldA
import org.seqra.ir.testing.usages.fields.FieldB
import org.seqra.ir.testing.usages.methods.MethodA
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

abstract class BaseSearchUsagesTest : BaseTest() {

    @Test
    fun `classes read fields`() {
        val usages = fieldsUsages<FieldA>(FieldUsageMode.READ)
        assertEquals(
            sortedMapOf(
                "a" to setOf(
                    "org.seqra.ir.testing.usages.fields.FieldA#<init>",
                    "org.seqra.ir.testing.usages.fields.FieldA#isPositive",
                    "org.seqra.ir.testing.usages.fields.FieldA#useCPrivate",
                    "org.seqra.ir.testing.usages.fields.FieldAImpl#hello"
                ),
                "b" to setOf(
                    "org.seqra.ir.testing.usages.fields.FieldA#isPositive",
                    "org.seqra.ir.testing.usages.fields.FieldA#useA",
                ),
                "fieldB" to setOf(
                    "org.seqra.ir.testing.usages.fields.FieldA#useCPrivate",
                )
            ),
            usages
        )
    }

    @Test
    fun `classes write fields`() {
        val usages = fieldsUsages<FieldA>()
        assertEquals(
            sortedMapOf(
                "a" to setOf(
                    "org.seqra.ir.testing.usages.fields.FieldA#<init>",
                    "org.seqra.ir.testing.usages.fields.FieldA#useA",
                ),
                "b" to setOf(
                    "org.seqra.ir.testing.usages.fields.FieldA#<init>"
                ),
                "fieldB" to setOf(
                    "org.seqra.ir.testing.usages.fields.FieldA#<init>",
                )
            ),
            usages
        )
    }

    @Test
    fun `classes write fields with rebuild`() {
        val time = measureTimeMillis {
            runBlocking {
                cp.db.rebuildFeatures()
            }
        }
        println("Features rebuild in ${time}ms")
        val usages = fieldsUsages<FieldA>()
        assertEquals(
            sortedMapOf(
                "a" to setOf(
                    "org.seqra.ir.testing.usages.fields.FieldA#<init>",
                    "org.seqra.ir.testing.usages.fields.FieldA#useA",
                ),
                "b" to setOf(
                    "org.seqra.ir.testing.usages.fields.FieldA#<init>"
                ),
                "fieldB" to setOf(
                    "org.seqra.ir.testing.usages.fields.FieldA#<init>",
                )
            ),
            usages
        )
    }

    @Test
    fun `classes write fields coupled`() {
        val usages = fieldsUsages<FieldB>()
        assertEquals(
            sortedMapOf(
                "c" to setOf(
                    "org.seqra.ir.testing.usages.fields.FakeFieldA#useCPrivate",
                    "org.seqra.ir.testing.usages.fields.FieldA#useCPrivate",
                    "org.seqra.ir.testing.usages.fields.FieldB#<init>",
                    "org.seqra.ir.testing.usages.fields.FieldB#useCPrivate",
                )
            ),
            usages
        )
    }

    @Test
    fun `classes methods usages`() {
        val usages = methodsUsages<MethodA>()
        assertEquals(
            sortedMapOf(
                CONSTRUCTOR to setOf(
                    "org.seqra.ir.testing.usages.methods.MethodB#hoho",
                    "org.seqra.ir.testing.usages.methods.MethodC#<init>"
                ),
                "hello" to setOf(
                    "org.seqra.ir.testing.usages.methods.MethodB#hoho",
                    "org.seqra.ir.testing.usages.methods.MethodC#hello",
                )
            ),
            usages
        )
    }

    @Test
    fun `find usages of Runnable#run method`() {
        runBlocking {
            val ext = cp.usagesExt()
            val runMethod = cp.findClass<Runnable>().declaredMethods.first()
            assertEquals("run", runMethod.name)
            val result = ext.findUsages(runMethod).toList()
            assertTrue(result.size > 50)
        }
    }

    @Test
    fun `find usages of System#out field`() {
        runBlocking {
            val ext = cp.usagesExt()
            val invokeStaticField = cp.findClass<System>().declaredFields.first { it.name == "out" }
            val result = ext.findUsages(invokeStaticField, FieldUsageMode.READ).toList()
            assertTrue(result.size > 500)
        }
    }

    private inline fun <reified T> fieldsUsages(mode: FieldUsageMode = FieldUsageMode.WRITE): Map<String, Set<String>> {
        return runBlocking {
            with(cp.usagesExt()) {
                val classId = cp.findClass<T>()

                val fields = classId.declaredFields

                fields.associate {
                    it.name to findUsages(it, mode).map { it.enclosingClass.name + "#" + it.name }.toSortedSet()
                }.filterNot { it.value.isEmpty() }.toSortedMap()
            }
        }
    }

    private inline fun <reified T> methodsUsages(): Map<String, Set<String>> {
        return runBlocking {
            with(cp.usagesExt()) {
                val classId = cp.findClass<T>()
                val methods = classId.declaredMethods

                methods.associate {
                    it.name to findUsages(it).map { it.enclosingClass.name + "#" + it.name }.toSortedSet()
                }.filterNot { it.value.isEmpty() }.toSortedMap()
            }
        }
    }

}

class SearchUsagesTest : BaseSearchUsagesTest() {
    companion object : WithGlobalDb()
}

class SearchUsagesSQLiteTest : BaseSearchUsagesTest() {
    companion object : WithGlobalSQLiteDb()
}
