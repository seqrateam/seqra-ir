package org.seqra.ir.testing.features

import kotlinx.coroutines.runBlocking
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.ext.findClass
import org.seqra.ir.api.jvm.ext.packageName
import org.seqra.ir.impl.features.Builders
import org.seqra.ir.impl.features.InMemoryHierarchy
import org.seqra.ir.impl.features.buildersExtension
import org.seqra.ir.testing.BaseTest
import org.seqra.ir.testing.WithGlobalDbImmutable
import org.seqra.ir.testing.WithSQLiteDb
import org.seqra.ir.testing.builders.Hierarchy.HierarchyInterface
import org.seqra.ir.testing.builders.Interfaces.Interface
import org.seqra.ir.testing.builders.Simple
import org.jooq.DSLContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnJre
import org.junit.jupiter.api.condition.JRE
import javax.xml.parsers.DocumentBuilderFactory

open class BuildersTest : BaseTest() {

    companion object : WithGlobalDbImmutable()

    private val ext = runBlocking {
        cp.buildersExtension()
    }

    @Test
    fun `simple find builders`() {
        val builders = ext.findBuildMethods(cp.findClass<Simple>()).toList()
        assertEquals(1, builders.size)
        assertEquals("build", builders.first().name)
    }

    @Test
    fun `java package is not indexed`() {
        val builders = ext.findBuildMethods(cp.findClass<ArrayList<*>>())
        assertFalse(builders.iterator().hasNext())
    }

    @Test
    fun `method parameters is took into account`() {
        val builders = ext.findBuildMethods(cp.findClass<Interface>()).toList()
        assertEquals(1, builders.size)
        assertEquals("build1", builders.first().name)
    }

    @Test
    @DisabledOnJre(JRE.JAVA_8)
    fun `works for DocumentBuilderFactory`() {
        val builders = ext.findBuildMethods(cp.findClass<DocumentBuilderFactory>()).toList()
        val expected = builders.map { it.loggable }
        assertTrue(expected.contains("javax.xml.parsers.DocumentBuilderFactory#newDefaultInstance"))
        assertTrue(expected.contains("javax.xml.parsers.DocumentBuilderFactory#newInstance"))
    }

    @Test
    fun `works for DocumentBuilderFactory for java 8`() {
        val builders = ext.findBuildMethods(cp.findClass<DocumentBuilderFactory>()).toList()
        val expected = builders.map { it.loggable }
        assertTrue(expected.contains("javax.xml.parsers.DocumentBuilderFactory#newInstance"))
    }

    @Test
    fun `works for jooq`() {
        val builders = ext.findBuildMethods(cp.findClass<DSLContext>()).toList()
        assertEquals("org.jooq.impl.DSL#using", builders.first {
            it.enclosingClass.packageName.startsWith("org.jooq")
        }.loggable)
    }

    @Test
    fun `works for methods returns subclasses`() {
        val builders = ext.findBuildMethods(cp.findClass<HierarchyInterface>(), includeSubclasses = true).toList()
        assertEquals(1, builders.size)
        assertEquals("org.seqra.ir.testing.builders.Hierarchy#build", builders.first().loggable)
    }

    private val JIRMethod.loggable get() = enclosingClass.name + "#" + name
}

class BuildersSQLiteTest : BuildersTest() {
    companion object : WithSQLiteDb(Builders, InMemoryHierarchy())
}
