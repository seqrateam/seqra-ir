package org.seqra.ir.testing

import kotlinx.coroutines.runBlocking
import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.JIRDatabase
import org.seqra.ir.api.jvm.ext.JAVA_OBJECT
import org.seqra.ir.impl.features.hierarchyExt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@LifecycleTest
abstract class SubClassesTest : BaseTest() {

    companion object : WithGlobalDbImmutable()

    protected abstract val withDB: JIRDatabaseHolder

    private val anotherDb: JIRDatabase get() = withDB.db
    private val anotherCp: JIRClasspath by lazy {
        runBlocking {
            anotherDb.awaitBackgroundJobs()
            anotherDb.classpath(allClasspath)
        }
    }

    @Test
    fun `Object subclasses should be the same`() {
        runBlocking {
            val hierarchy = cp.hierarchyExt()
            val anotherHierarchy = anotherCp.hierarchyExt()
            Assertions.assertEquals(
                hierarchy.findSubClasses(JAVA_OBJECT, false, includeOwn = true).count(),
                anotherHierarchy.findSubClasses(JAVA_OBJECT, false, includeOwn = true).count()
            )
        }
    }

    @AfterEach
    fun `cleanup another db`() = runBlocking {
        withDB.cleanup()
    }
}

class SubClassesNoSqlTest : SubClassesTest() {

    override val withDB: JIRDatabaseHolder = WithDbImmutable()
}

class SubClassesSQLiteTest : SubClassesTest() {

    override val withDB: JIRDatabaseHolder = WithSQLiteDb()
}
