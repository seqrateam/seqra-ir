package org.seqra.ir.testing.cfg

import org.seqra.ir.api.jvm.JavaVersion
import org.seqra.ir.api.jvm.JIRClassType
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.JIRTypedMethod
import org.seqra.ir.api.jvm.TypeName
import org.seqra.ir.api.jvm.cfg.JIRAssignInst
import org.seqra.ir.api.jvm.cfg.JIRCallExpr
import org.seqra.ir.api.jvm.cfg.JIRCallInst
import org.seqra.ir.api.jvm.cfg.JIRCatchInst
import org.seqra.ir.api.jvm.cfg.JIREnterMonitorInst
import org.seqra.ir.api.jvm.cfg.JIRExitMonitorInst
import org.seqra.ir.api.jvm.cfg.JIRExpr
import org.seqra.ir.api.jvm.cfg.JIRExprVisitor
import org.seqra.ir.api.jvm.cfg.JIRGotoInst
import org.seqra.ir.api.jvm.cfg.JIRGraph
import org.seqra.ir.api.jvm.cfg.JIRIfInst
import org.seqra.ir.api.jvm.cfg.JIRInst
import org.seqra.ir.api.jvm.cfg.JIRInstVisitor
import org.seqra.ir.api.jvm.cfg.JIRReturnInst
import org.seqra.ir.api.jvm.cfg.JIRSpecialCallExpr
import org.seqra.ir.api.jvm.cfg.JIRSwitchInst
import org.seqra.ir.api.jvm.cfg.JIRTerminatingInst
import org.seqra.ir.api.jvm.cfg.JIRThrowInst
import org.seqra.ir.api.jvm.cfg.JIRVirtualCallExpr
import org.seqra.ir.api.jvm.ext.HierarchyExtension
import org.seqra.ir.api.jvm.ext.findClass
import org.seqra.ir.api.jvm.ext.toType
import org.seqra.ir.impl.JIRClasspathImpl
import org.seqra.ir.impl.JIRDatabaseImpl
import org.seqra.ir.impl.bytecode.JIRClassOrInterfaceImpl
import org.seqra.ir.impl.bytecode.JIRMethodImpl
import org.seqra.ir.impl.cfg.JIRBlockGraphImpl
import org.seqra.ir.impl.cfg.JIRInstListBuilder
import org.seqra.ir.impl.cfg.RawInstListBuilder
import org.seqra.ir.impl.cfg.Simplifier
import org.seqra.ir.impl.cfg.util.ExprMapper
import org.seqra.ir.impl.features.classpaths.ClasspathCache
import org.seqra.ir.impl.features.classpaths.StringConcatSimplifier
import org.seqra.ir.impl.features.classpaths.UnknownClasses
import org.seqra.ir.impl.fs.JarLocation
import org.seqra.ir.testing.WithDbImmutable
import org.seqra.ir.testing.WithSQLiteDb
import org.seqra.ir.testing.asmLib
import org.seqra.ir.testing.commonsCompressLib
import org.seqra.ir.testing.guavaLib
import org.seqra.ir.testing.jgitLib
import org.seqra.ir.testing.kotlinStdLib
import org.seqra.ir.testing.kotlinxCoroutines
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import java.io.File

class OverridesResolver(
    private val hierarchyExtension: HierarchyExtension,
) : JIRExprVisitor.Default<Sequence<JIRTypedMethod>>,
    JIRInstVisitor.Default<Sequence<JIRTypedMethod>> {

    override fun defaultVisitJIRExpr(expr: JIRExpr): Sequence<JIRTypedMethod> {
        return emptySequence()
    }

    override fun defaultVisitJIRInst(inst: JIRInst): Sequence<JIRTypedMethod> {
        return emptySequence()
    }

    private fun JIRClassType.getMethod(name: String, argTypes: List<TypeName>, returnType: TypeName): JIRTypedMethod {
        return methods.firstOrNull { typedMethod ->
            val jIRMethod = typedMethod.method
            jIRMethod.name == name &&
                    jIRMethod.returnType.typeName == returnType.typeName &&
                    jIRMethod.parameters.map { param -> param.type.typeName } == argTypes.map { it.typeName }
        } ?: error("Could not find a method with correct signature")
    }

    private val JIRMethod.typedMethod: JIRTypedMethod
        get() {
            val klass = enclosingClass.toType()
            return klass.getMethod(name, parameters.map { it.type }, returnType)
        }

    override fun visitJIRAssignInst(inst: JIRAssignInst): Sequence<JIRTypedMethod> {
        if (inst.rhv is JIRCallExpr) return inst.rhv.accept(this)
        return emptySequence()
    }

    override fun visitJIRCallInst(inst: JIRCallInst): Sequence<JIRTypedMethod> {
        return inst.callExpr.accept(this)
    }

    override fun visitJIRVirtualCallExpr(expr: JIRVirtualCallExpr): Sequence<JIRTypedMethod> {
        return hierarchyExtension.findOverrides(expr.method.method).map { it.typedMethod }
    }

    override fun visitJIRSpecialCallExpr(expr: JIRSpecialCallExpr): Sequence<JIRTypedMethod> {
        return hierarchyExtension.findOverrides(expr.method.method).map { it.typedMethod }
    }

}

class JIRGraphChecker(
    val method: JIRMethod,
    val jIRGraph: JIRGraph,
) : JIRInstVisitor<Unit> {

    fun check() {
        try {
            jIRGraph.entry
        } catch (e: Exception) {
            println(
                "Fail on method ${method.enclosingClass.simpleName}#${method.name}(${
                    method.parameters.joinToString(
                        ","
                    ) { it.type.typeName }
                })"
            )
            throw e
        }
        assertTrue(jIRGraph.exits.all { it is JIRTerminatingInst })

        jIRGraph.forEach { it.accept(this) }

        checkBlocks()
    }

    fun checkBlocks() {
        val blockGraph = jIRGraph.blockGraph()

        val entry = assertDoesNotThrow { blockGraph.entry }
        for (block in blockGraph) {
            if (block != entry) {
                when (jIRGraph.inst(block.start)) {
                    is JIRCatchInst -> {
                        assertTrue(blockGraph.predecessors(block).isEmpty())
                        assertTrue(blockGraph.throwers(block).isNotEmpty())
                    }

                    else -> {
                        assertTrue(blockGraph.predecessors(block).isNotEmpty())
                        assertTrue(blockGraph.throwers(block).isEmpty())
                    }
                }
            }
            assertDoesNotThrow { blockGraph.instructions(block).map { jIRGraph.catchers(it) }.toSet().single() }
            if (jIRGraph.inst(block.end) !is JIRTerminatingInst) {
                assertTrue(blockGraph.successors(block).isNotEmpty())
            }
        }
    }

    override fun visitExternalJIRInst(inst: JIRInst) {
        // Do nothing
    }

    override fun visitJIRAssignInst(inst: JIRAssignInst) {
        if (inst != jIRGraph.entry) {
            assertTrue(jIRGraph.predecessors(inst).isNotEmpty())
        }
        assertEquals(setOf(jIRGraph.next(inst)), jIRGraph.successors(inst))
        assertTrue(jIRGraph.catchers(inst).all { catch ->
            inst in catch.throwers.map { thrower -> jIRGraph.inst(thrower) }.toSet()
        })
        assertTrue(jIRGraph.throwers(inst).isEmpty())
    }

    override fun visitJIREnterMonitorInst(inst: JIREnterMonitorInst) {
        if (inst != jIRGraph.entry) {
            assertTrue(jIRGraph.predecessors(inst).isNotEmpty())
        }
        assertEquals(setOf(jIRGraph.next(inst)), jIRGraph.successors(inst))
        assertTrue(jIRGraph.catchers(inst).all { catch ->
            inst in catch.throwers.map { thrower -> jIRGraph.inst(thrower) }.toSet()
        })
        assertTrue(jIRGraph.throwers(inst).isEmpty())
    }

    override fun visitJIRExitMonitorInst(inst: JIRExitMonitorInst) {
        if (inst != jIRGraph.entry) {
            assertTrue(jIRGraph.predecessors(inst).isNotEmpty())
        }
        assertEquals(setOf(jIRGraph.next(inst)), jIRGraph.successors(inst))
        assertTrue(jIRGraph.catchers(inst).all { catch ->
            inst in catch.throwers.map { thrower -> jIRGraph.inst(thrower) }.toSet()
        })
        assertTrue(jIRGraph.throwers(inst).isEmpty())
    }

    override fun visitJIRCallInst(inst: JIRCallInst) {
        if (inst != jIRGraph.entry) {
            assertTrue(jIRGraph.predecessors(inst).isNotEmpty())
        }
        assertEquals(setOf(jIRGraph.next(inst)), jIRGraph.successors(inst))
        assertTrue(jIRGraph.catchers(inst).all { catch ->
            inst in catch.throwers.map { thrower -> jIRGraph.inst(thrower) }.toSet()
        })
        assertTrue(jIRGraph.throwers(inst).isEmpty())
    }

    override fun visitJIRReturnInst(inst: JIRReturnInst) {
        if (inst != jIRGraph.entry) {
            assertTrue(jIRGraph.predecessors(inst).isNotEmpty())
        }
        assertEquals(emptySet<JIRInst>(), jIRGraph.successors(inst))
        assertTrue(jIRGraph.catchers(inst).all { catch ->
            inst in catch.throwers.map { thrower -> jIRGraph.inst(thrower) }.toSet()
        })
        assertTrue(jIRGraph.throwers(inst).isEmpty())
    }

    override fun visitJIRThrowInst(inst: JIRThrowInst) {
        if (inst != jIRGraph.entry) {
            assertTrue(jIRGraph.predecessors(inst).isNotEmpty())
        }
        assertEquals(emptySet<JIRInst>(), jIRGraph.successors(inst))
        assertTrue(jIRGraph.catchers(inst).all { catch ->
            inst in catch.throwers.map { thrower -> jIRGraph.inst(thrower) }.toSet()
        })
        assertTrue(jIRGraph.throwers(inst).isEmpty())
    }

    override fun visitJIRCatchInst(inst: JIRCatchInst) {
        assertEquals(emptySet<JIRInst>(), jIRGraph.predecessors(inst))
        assertTrue(jIRGraph.successors(inst).isNotEmpty())
        assertTrue(jIRGraph.throwers(inst).all { thrower ->
            inst in jIRGraph.catchers(thrower)
        })
    }

    override fun visitJIRGotoInst(inst: JIRGotoInst) {
        if (inst != jIRGraph.entry) {
            assertTrue(jIRGraph.predecessors(inst).isNotEmpty())
        }
        assertEquals(setOf(jIRGraph.inst(inst.target)), jIRGraph.successors(inst))
        assertTrue(jIRGraph.catchers(inst).all { catch ->
            inst in catch.throwers.map { thrower -> jIRGraph.inst(thrower) }.toSet()
        })
        assertTrue(jIRGraph.throwers(inst).isEmpty())
    }

    override fun visitJIRIfInst(inst: JIRIfInst) {
        if (inst != jIRGraph.entry) {
            assertTrue(jIRGraph.predecessors(inst).isNotEmpty())
        }
        assertEquals(
            setOf(
                jIRGraph.inst(inst.trueBranch),
                jIRGraph.inst(inst.falseBranch)
            ),
            jIRGraph.successors(inst)
        )
        assertTrue(jIRGraph.catchers(inst).all { catch ->
            inst in catch.throwers.map { thrower -> jIRGraph.inst(thrower) }.toSet()
        })
        assertTrue(jIRGraph.throwers(inst).isEmpty())
    }

    override fun visitJIRSwitchInst(inst: JIRSwitchInst) {
        if (inst != jIRGraph.entry) {
            assertTrue(jIRGraph.predecessors(inst).isNotEmpty())
        }
        assertEquals(
            inst.branches.values.map { jIRGraph.inst(it) }.toSet() + jIRGraph.inst(inst.default),
            jIRGraph.successors(inst)
        )

        assertTrue(jIRGraph.catchers(inst).all { catch ->
            inst in catch.throwers.map { thrower -> jIRGraph.inst(thrower) }.toSet()
        })
        assertTrue(jIRGraph.throwers(inst).isEmpty())
    }
}

open class IRTest : BaseInstructionsTest() {

    companion object : WithDbImmutable(StringConcatSimplifier, UnknownClasses)

    @Test
    fun `get ir of simple method`() {
        testClass(cp.findClass<IRExamples>())
    }

    @Test
    fun `arrays methods`() {
        testClass(cp.findClass<JavaArrays>())
    }

    @Test
    fun `get ir of algorithms lesson 1`() {
        testClass(cp.findClass<JavaTasks>())
    }

    @Test
    fun `get ir of binary search tree`() {
        testClass(cp.findClass<BinarySearchTree<*>>())
        testClass(cp.findClass<BinarySearchTree<*>.BinarySearchTreeIterator>())
    }

    @Test
    fun `get ir of random class`() {
        val clazz = cp.findClass("kotlinx.coroutines.channels.ChannelsKt__DeprecatedKt\$filterIndexed\$1")
        val method = clazz.declaredMethods.first { it.name == "invokeSuspend" }
        JIRGraphChecker(method, method.flowGraph()).check()
    }

    @Test
    fun `get ir of self`() {
        testClass(cp.findClass<JIRClasspathImpl>())
        testClass(cp.findClass<JIRClassOrInterfaceImpl>())
        testClass(cp.findClass<JIRMethodImpl>())
        testClass(cp.findClass<RawInstListBuilder>())
        testClass(cp.findClass<Simplifier>())
        testClass(cp.findClass<JIRDatabaseImpl>())
        testClass(cp.findClass<ExprMapper>())
        testClass(cp.findClass<JIRInstListBuilder>())
        testClass(cp.findClass<JIRBlockGraphImpl>())
    }

    @Test
    fun `get ir of guava`() {
        runAlongLib(guavaLib)
    }

    @Test
    fun `get ir of jgit`() {
        runAlongLib(jgitLib)
    }

    @Test
    fun `get ir of commons compress`() {
        runAlongLib(commonsCompressLib)
    }

    @Test
    fun `get ir of asm`() {
        runAlongLib(asmLib, muteGraphChecker = true)
    }

    @Test
    fun `get ir of kotlinx-coroutines`() {
        runAlongLib(kotlinxCoroutines, false)
    }

    @Test
    fun `get ir of kotlin stdlib`() {
        runAlongLib(kotlinStdLib, false)
    }

    @AfterEach
    fun printStats() {
        cp.features!!.filterIsInstance<ClasspathCache>().forEach {
            it.dumpStats()
        }
    }

    private fun runAlongLib(file: File, validateLineNumbers: Boolean = true, muteGraphChecker: Boolean = false) {
        println("Run along: ${file.absolutePath}")

        val classes = JarLocation(file, isRuntime = false, object : JavaVersion {
            override val majorVersion: Int
                get() = 8
        }).classes
        assertFalse(classes.isEmpty())
        classes.forEach {
            val clazz = cp.findClass(it.key)
            if (!clazz.isAnnotation && !clazz.isInterface) {
                println("Testing class: ${it.key}")
                testClass(clazz, validateLineNumbers, muteGraphChecker)
            }
        }
    }
}

class IRSQLiteTest : IRTest() {

    companion object : WithSQLiteDb(StringConcatSimplifier, UnknownClasses)
}