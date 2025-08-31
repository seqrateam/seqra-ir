package org.seqra.ir.approximations

import org.seqra.ir.api.jvm.JavaVersion
import org.seqra.ir.api.jvm.cfg.JIRAssignInst
import org.seqra.ir.api.jvm.cfg.JIRCallInst
import org.seqra.ir.api.jvm.cfg.JIRFieldRef
import org.seqra.ir.api.jvm.cfg.JIRRawAssignInst
import org.seqra.ir.api.jvm.cfg.JIRRawCallInst
import org.seqra.ir.api.jvm.cfg.JIRRawFieldRef
import org.seqra.ir.api.jvm.ext.findClass
import org.seqra.ir.api.jvm.ext.findDeclaredFieldOrNull
import org.seqra.ir.approximation.Approximations
import org.seqra.ir.approximation.JIREnrichedVirtualField
import org.seqra.ir.approximation.JIREnrichedVirtualMethod
import org.seqra.ir.approximation.toApproximationName
import org.seqra.ir.approximation.toOriginalName
import org.seqra.ir.approximations.target.KotlinClass
import org.seqra.ir.impl.fs.JarLocation
import org.seqra.ir.testing.BaseTest
import org.seqra.ir.testing.WithDb
import org.seqra.ir.testing.WithSQLiteDb
import org.seqra.ir.testing.guavaLib
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

private val approximations = Approximations(emptyList())

open class ApproximationsTest : BaseTest() {

    // ApproximationsTest designed to work only with applied ApproximationIndexer
    // So, if WithDbImmutable is used then indexing would be skipped and tests would fail
    companion object : WithDb(approximations)

    @Test
    fun `kotlin approximation`() {
        val classes = cp.findClass<KotlinClass>()

        val originalClassName = KotlinClass::class.qualifiedName!!.toOriginalName()
        val approximation = approximations.findApproximationByOriginOrNull(originalClassName)

        assertNotNull(approximation)
        assertEquals(classes.name, approximations.findOriginalByApproximationOrNull(approximation!!.toApproximationName()))
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @Test
    fun `java approximation`() {
        val classec = cp.findClass<Integer>()

        val originalClassName = "java.lang.Integer".toOriginalName()
        val approximation = approximations.findApproximationByOriginOrNull(originalClassName)

        assertNotNull(approximation)
        assertEquals(classec.name, approximations.findOriginalByApproximationOrNull(approximation!!.toApproximationName()))
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    @Test
    fun `integer approximation`() {
        val classec = cp.findClass<Integer>()

        val field = classec.findDeclaredFieldOrNull("value")
        assertTrue(field is JIREnrichedVirtualField)

        val method = classec.declaredMethods
            .filter { it.name == "valueOf" }
            .singleOrNull { it is JIREnrichedVirtualMethod }
        assertNotNull(method)
    }

    @Test
    fun `replaced fields`() {
        val classec = cp.findClass<KotlinClass>()
        val fields = classec.declaredFields

        assertTrue(fields.size == 7)

        val (virtualFields, originalFields) = fields.partition { it is JIREnrichedVirtualField }
        val virtualFieldsNames = virtualFields.map { it.name }
        val originalFieldsNames = originalFields.map { it.name }

        assertTrue(virtualFields.size == 4)
        assertTrue("fieldToReplace" in virtualFieldsNames)
        assertTrue("sameApproximation" in virtualFieldsNames)
        assertTrue("anotherApproximation" in virtualFieldsNames)
        assertTrue("artificialField" in virtualFieldsNames)

        assertTrue(originalFields.size == 3)
        assertTrue("sameApproximationTarget" in originalFieldsNames)
        assertTrue("anotherApproximationTarget" in originalFieldsNames)
        assertTrue("fieldWithoutApproximation" in originalFieldsNames)

        assertEquals(fields, classec.declaredFields)
    }

    @Test
    fun `replaced methods`() {
        val classec = cp.findClass<KotlinClass>()
        val methods = classec.declaredMethods

        assertTrue(methods.size == 8)

        val (virtualMethods, originalMethods) = methods.partition { it is JIREnrichedVirtualMethod }
        // we can use just names here since we don't have overload in both original and target classes
        val virtualMethodsNames = virtualMethods.map { it.name }
        val originalMethodsNames = originalMethods.map { it.name }

        assertTrue(virtualMethods.size == 7)
        assertTrue("replaceBehaviour" in virtualMethodsNames)
        assertTrue("artificialMethod" in virtualMethodsNames)
        assertTrue("useArtificialField" in virtualMethodsNames)
        assertTrue("useSameApproximationTarget" in virtualMethodsNames)
        assertTrue("useAnotherApproximationTarget" in virtualMethodsNames)
        assertTrue("useFieldWithoutApproximation" in virtualMethodsNames)
        assertTrue("<init>" in virtualMethodsNames)

        assertTrue(originalMethods.size == 1)
        assertTrue("methodWithoutApproximation" in originalMethodsNames)

        assertEquals(methods, classec.declaredMethods)
    }

    @Test
    fun `replace approximations in methodBody`() {
        val classec = cp.findClass<KotlinClass>()
        val method = classec.declaredMethods.single { it.name == "useSameApproximationTarget" }

        val graph = method.flowGraph()
        val instructions = graph.instructions
        val rawInstructions = method.rawInstList

        assertTrue(method.enclosingClass === classec)
        assertTrue("KotlinClassApprox" !in method.description)

        val types = hashSetOf<String>()

        types += method.returnType.typeName

        val callInsts = instructions.filterIsInstance<JIRCallInst>()
        val assignInsts = instructions.filterIsInstance<JIRAssignInst>()

        val rawCallInsts = rawInstructions.filterIsInstance<JIRRawCallInst>()
        val rawAssignInsts = rawInstructions.filterIsInstance<JIRRawAssignInst>()

        callInsts.forEach { inst ->
            val location = inst.location
            assertTrue(location.method === method)

            val callExpr = inst.callExpr
            types += callExpr.type.typeName
            types += callExpr.method.returnType.typeName
            types += callExpr.method.enclosingType.typeName
            types += callExpr.method.typeArguments.map { it.typeName }
        }

        assignInsts.forEach { inst ->
            val location = inst.location
            assertTrue(location.method === method)

            types += inst.lhv.type.typeName
            val rhv = inst.rhv
            if (rhv is JIRFieldRef) {
                types += rhv.type.typeName
                types += rhv.field.type.typeName
                types += rhv.field.enclosingType.typeName
            }
        }

        rawCallInsts.forEach { inst ->
            val location = inst.owner
            assertTrue(location === method)

            val callExpr = inst.callExpr
            types += callExpr.typeName.typeName
            types += callExpr.returnType.typeName
            types += callExpr.args.map { it.typeName.typeName }
        }

        rawAssignInsts.forEach { inst ->
            val location = inst.owner
            assertTrue(location === method)

            types += inst.lhv.typeName.typeName
            val rhv = inst.rhv
            if (rhv is JIRRawFieldRef) {
                types += rhv.typeName.typeName
                rhv.instance?.typeName?.let { types += it.typeName }
                types += rhv.declaringClass.typeName
            }
        }

        assertTrue(types.none { approximations.findOriginalByApproximationOrNull(it.toApproximationName()) != null })
    }

    @Test
    fun `run around guava`() {
        runAlongLib(guavaLib)
    }

    private fun runAlongLib(file: File) {
        val classes = JarLocation(file, isRuntime = false, object : JavaVersion {
            override val majorVersion: Int
                get() = 8
        }).classes
        assertFalse(classes.isEmpty())
        classes.forEach {
            val clazz = cp.findClass(it.key)
            if (!clazz.isAnnotation && !clazz.isInterface) {
                println("Testing class: ${it.key}")
                clazz.declaredMethods.forEach {
                    it.flowGraph()
                }
            }
        }
    }
}

@Disabled("support approximation versions for SQL persistence")
class ApproximationsSQLiteTest : ApproximationsTest() {

    companion object : WithSQLiteDb(approximations)
}
