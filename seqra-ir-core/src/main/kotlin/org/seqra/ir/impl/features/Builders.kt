package org.seqra.ir.impl.features

import org.seqra.ir.api.jvm.ByteCodeIndexer
import org.seqra.ir.api.jvm.ClassSource
import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.JIRDatabase
import org.seqra.ir.api.jvm.JIRDatabasePersistence
import org.seqra.ir.api.jvm.JIRFeature
import org.seqra.ir.api.jvm.JIRSignal
import org.seqra.ir.api.jvm.RegisteredLocation
import org.seqra.ir.api.jvm.ext.jvmPrimitiveNames
import org.seqra.ir.api.storage.StorageContext
import org.seqra.ir.api.storage.ers.compressed
import org.seqra.ir.api.storage.ers.nonSearchable
import org.seqra.ir.impl.fs.PersistenceClassSource
import org.seqra.ir.impl.fs.className
import org.seqra.ir.impl.storage.dslContext
import org.seqra.ir.impl.storage.ers.filterDeleted
import org.seqra.ir.impl.storage.ers.filterLocations
import org.seqra.ir.impl.storage.ers.toClassSource
import org.seqra.ir.impl.storage.execute
import org.seqra.ir.impl.storage.executeQueries
import org.seqra.ir.impl.storage.jooq.tables.references.BUILDERS
import org.seqra.ir.impl.storage.jooq.tables.references.CLASSES
import org.seqra.ir.impl.storage.jooq.tables.references.SYMBOLS
import org.seqra.ir.impl.storage.runBatch
import org.seqra.ir.impl.storage.txn
import org.seqra.ir.impl.storage.withoutAutoCommit
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

private val MethodNode.isGetter: Boolean
    get() {
        return name.startsWith("get")
    }

private val Int.isPublic get() = this and Opcodes.ACC_PUBLIC != 0
private val Int.isStatic get() = this and Opcodes.ACC_STATIC != 0

private data class BuilderMethod(
    val callerClass: String,
    val methodOffset: Int,
    val priority: Int
)

class BuildersIndexer(val persistence: JIRDatabasePersistence, private val location: RegisteredLocation) :
    ByteCodeIndexer {

    // class -> (caller_class, offset, priority)
    private val potentialBuilders = hashMapOf<String, HashSet<BuilderMethod>>()

    override fun index(classNode: ClassNode) {
        val callerClass = classNode.name
        classNode.methods.forEachIndexed { index, methodNode ->
            val isStatic = methodNode.access.isStatic
            if (methodNode.access.isPublic && !methodNode.isGetter) {
                val returnType = Type.getMethodType(methodNode.desc).returnType.internalName
                if (
                    !jvmPrimitiveNames.contains(returnType) && // not interesting in primitives
                    !returnType.startsWith("[") && // not interesting in arrays
                    !returnType.startsWith("java/") // not interesting in java package classes
                ) {
                    val noParams = Type.getArgumentTypes(methodNode.desc).isNullOrEmpty()
                    val isBuildName = methodNode.name.equals("build")
                    val priority = when {
                        isStatic && noParams && returnType == callerClass -> 15
                        isStatic && noParams -> 10
                        isBuildName && noParams -> 7
                        isStatic -> 5
                        isBuildName -> 3
                        else -> 0
                    }
                    potentialBuilders.getOrPut(returnType) { hashSetOf() }
                        .add(BuilderMethod(callerClass, index, priority))
                }
            }
        }
    }


    override fun flush(context: StorageContext) {
        context.execute(
            sqlAction = {
                context.dslContext.withoutAutoCommit { conn ->
                    conn.runBatch(BUILDERS) {
                        potentialBuilders.forEach { (calleeClass, builders) ->
                            val calleeId = calleeClass.className
                            builders.forEach {
                                val (callerClass, offset, priority) = it
                                val callerId = callerClass.className
                                setString(1, calleeId)
                                setString(2, callerId)
                                setInt(3, priority)
                                setInt(4, offset)
                                setLong(5, location.id)
                                addBatch()
                            }
                        }
                    }
                }
            },
            noSqlAction = {
                val txn = context.txn
                potentialBuilders.forEach { (returnedClassInternalName, builders) ->
                    val returnTypeId = persistence.findSymbolId(returnedClassInternalName.className)
                    builders.forEach { builder ->
                        val entity = txn.newEntity(BuilderEntity.BUILDER_ENTITY_TYPE)
                        entity[BuilderEntity.BUILDER_LOCATION_ID_PROPERTY] = location.id.compressed
                        entity[BuilderEntity.RETURNED_CLASS_NAME_ID_PROPERTY] = returnTypeId.compressed
                        entity[BuilderEntity.BUILDER_CLASS_NAME_ID] =
                            persistence.findSymbolId(builder.callerClass.className).compressed.nonSearchable
                        entity[BuilderEntity.METHOD_OFFSET_PROPERTY] = builder.methodOffset.compressed.nonSearchable
                        entity[BuilderEntity.PRIORITY_PROPERTY] = builder.priority.compressed.nonSearchable
                    }
                }
            }
        )
    }

}

data class BuildersResponse(
    val methodOffset: Int,
    val priority: Int,
    val source: ClassSource
)

object Builders : JIRFeature<Set<String>, BuildersResponse> {

    fun create(context: StorageContext, drop: Boolean) {
        context.execute(
            sqlAction = {
                val jooq = context.dslContext
                if (drop) {
                    jooq.executeQueries(dropScheme)
                }
                jooq.executeQueries(createScheme)
            },
            noSqlAction = {
                if (drop) {
                    context.txn.all(BuilderEntity.BUILDER_ENTITY_TYPE).deleteAll()
                }
            }
        )
    }

    private val createScheme = """
        CREATE TABLE IF NOT EXISTS "Builders"(
            "class_name"                    VARCHAR(256) NOT NULL,
            "builder_class_name"            VARCHAR(256) NOT NULL,
            "priority"                      INTEGER NOT NULL,
            "offset"      					INTEGER NOT NULL,
            "location_id"                   BIGINT NOT NULL,
        CONSTRAINT "fk_location_id" FOREIGN KEY ("location_id") REFERENCES "BytecodeLocations" ("id") ON DELETE CASCADE
        );
    """.trimIndent()

    private val createIndex = """
            CREATE INDEX IF NOT EXISTS "BuildersSearch" ON "Builders"(location_id, class_name, priority);
            CREATE INDEX IF NOT EXISTS "BuildersSorting" ON "Builders"(priority);
            CREATE INDEX IF NOT EXISTS "BuildersJoin" ON "Builders"(builder_class_name);
    """.trimIndent()

    private val dropScheme = """
            DROP TABLE IF EXISTS "Builders";
            DROP INDEX IF EXISTS "BuildersSearch";
            DROP INDEX IF EXISTS "BuildersSorting";
            DROP INDEX IF EXISTS "BuildersJoin";
    """.trimIndent()

    override fun onSignal(signal: JIRSignal) {
        when (signal) {
            is JIRSignal.BeforeIndexing -> {
                signal.jIRdb.persistence.write { context ->
                    if (signal.clearOnStart) {
                        context.execute(
                            sqlAction = { context.dslContext.executeQueries(dropScheme) },
                            noSqlAction = { context.txn.all(type = BuilderEntity.BUILDER_ENTITY_TYPE).deleteAll() }
                        )
                    }
                    context.execute(
                        sqlAction = { context.dslContext.executeQueries(createScheme) },
                        noSqlAction = { /* no-op */ }
                    )
                }
            }

            is JIRSignal.LocationRemoved -> {
                signal.jIRdb.persistence.write { context ->
                    context.execute(
                        sqlAction = {
                            context.dslContext.deleteFrom(BUILDERS).where(BUILDERS.LOCATION_ID.eq(signal.location.id))
                        },
                        noSqlAction = {
                            context.txn.find(
                                type = BuilderEntity.BUILDER_ENTITY_TYPE,
                                propertyName = BuilderEntity.BUILDER_LOCATION_ID_PROPERTY,
                                value = signal.location.id.compressed
                            ).deleteAll()
                        }
                    )
                }
            }

            is JIRSignal.AfterIndexing -> {
                signal.jIRdb.persistence.write { context ->
                    context.execute(
                        sqlAction = { context.dslContext.executeQueries(createIndex) },
                        noSqlAction = { /* no-op */ }
                    )
                }
            }

            is JIRSignal.Drop -> {
                signal.jIRdb.persistence.write { context ->
                    context.execute(
                        sqlAction = { context.dslContext.deleteFrom(BUILDERS).execute() },
                        noSqlAction = { context.txn.all(type = BuilderEntity.BUILDER_ENTITY_TYPE).deleteAll() }
                    )
                }
            }

            else -> Unit
        }
    }

    override suspend fun query(classpath: JIRClasspath, req: Set<String>): Sequence<BuildersResponse> {
        return syncQuery(classpath, req)
    }

    fun syncQuery(classpath: JIRClasspath, req: Set<String>): Sequence<BuildersResponse> {
        val locationIds = classpath.registeredLocationIds
        val persistence = classpath.db.persistence
        return sequence {
            val result = persistence.read { context ->
                context.execute(
                    sqlAction = {
                        val jooq = context.dslContext
                        jooq.select(BUILDERS.OFFSET, SYMBOLS.NAME, CLASSES.ID, CLASSES.LOCATION_ID, BUILDERS.PRIORITY)
                            .from(BUILDERS)
                            .join(SYMBOLS).on(SYMBOLS.NAME.eq(BUILDERS.BUILDER_CLASS_NAME))
                            .join(CLASSES)
                            .on(CLASSES.NAME.eq(SYMBOLS.ID).and(BUILDERS.LOCATION_ID.eq(CLASSES.LOCATION_ID)))
                            .where(
                                BUILDERS.CLASS_NAME.`in`(req).and(BUILDERS.LOCATION_ID.`in`(locationIds))
                            )
                            .limit(100)
                            .fetch()
                            .mapNotNull { (offset, className, classId, locationId, priority) ->
                                BuildersResponse(
                                    source = PersistenceClassSource(
                                        db = classpath.db,
                                        locationId = locationId!!,
                                        classId = classId!!,
                                        className = className!!,
                                    ),
                                    methodOffset = offset!!,
                                    priority = priority ?: 0
                                )
                            }
                    },
                    noSqlAction = {
                        val txn = context.txn
                        // TODO: review as resulting expression looks quite verbose and non-optimal
                        req.asSequence()
                            .map { returnedClassName -> persistence.findSymbolId(returnedClassName) }
                            .map { returnedClassNameId ->
                                txn.find(
                                    type = BuilderEntity.BUILDER_ENTITY_TYPE,
                                    propertyName = BuilderEntity.RETURNED_CLASS_NAME_ID_PROPERTY,
                                    value = returnedClassNameId.compressed
                                )
                            }
                            .flatten()
                            .mapNotNull { builder ->
                                val locationId = builder.getCompressed<Long>(BuilderEntity.BUILDER_LOCATION_ID_PROPERTY)
                                if (locationId != null && locationId in locationIds) builder to locationId else null
                            }
                            .flatMap { (builder, builderLocationId) ->
                                val builderClassNameId: Long =
                                    builder.getCompressedBlob<Long>(BuilderEntity.BUILDER_CLASS_NAME_ID)!!
                                txn.find("Class", "nameId", builderClassNameId.compressed)
                                    .filterLocations(builderLocationId)
                                    .filterDeleted()
                                    .map { builderClass ->
                                        BuildersResponse(
                                            source = builderClass.toClassSource(
                                                persistence = persistence,
                                                className = persistence.findSymbolName(builderClassNameId),
                                                nameId = builderClassNameId
                                            ),
                                            methodOffset = builder.getCompressedBlob<Int>(BuilderEntity.METHOD_OFFSET_PROPERTY)!!,
                                            priority = builder.getCompressedBlob<Int>(BuilderEntity.PRIORITY_PROPERTY)
                                                ?: 0
                                        )
                                    }
                            }.toList()
                    }
                )
            }.sortedByDescending { it.priority }
            yieldAll(result)
        }
    }

    override fun newIndexer(jIRdb: JIRDatabase, location: RegisteredLocation) =
        BuildersIndexer(jIRdb.persistence, location)


}

private object BuilderEntity {
    const val BUILDER_ENTITY_TYPE = "Builder"
    const val BUILDER_LOCATION_ID_PROPERTY = "builderLocationId"
    const val RETURNED_CLASS_NAME_ID_PROPERTY = "returnedClassNameId"
    const val BUILDER_CLASS_NAME_ID = "builderClassNameId"
    const val METHOD_OFFSET_PROPERTY = "methodOffset"
    const val PRIORITY_PROPERTY = "priority"
}
