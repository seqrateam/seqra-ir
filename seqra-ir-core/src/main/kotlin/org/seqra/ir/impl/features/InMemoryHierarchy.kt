package org.seqra.ir.impl.features

import org.seqra.ir.api.jvm.ByteCodeIndexer
import org.seqra.ir.api.jvm.ClassSource
import org.seqra.ir.api.jvm.JIRClassOrInterface
import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.JIRDatabase
import org.seqra.ir.api.jvm.JIRDatabasePersistence
import org.seqra.ir.api.jvm.JIRFeature
import org.seqra.ir.api.jvm.JIRSignal
import org.seqra.ir.api.jvm.RegisteredLocation
import org.seqra.ir.api.jvm.ext.JAVA_OBJECT
import org.seqra.ir.api.storage.StorageContext
import org.seqra.ir.api.storage.asSymbolId
import org.seqra.ir.api.storage.ers.compressed
import org.seqra.ir.api.storage.ers.links
import org.seqra.ir.impl.fs.PersistenceClassSource
import org.seqra.ir.impl.fs.className
import org.seqra.ir.impl.storage.BatchedSequence
import org.seqra.ir.impl.storage.defaultBatchSize
import org.seqra.ir.impl.storage.dslContext
import org.seqra.ir.impl.storage.ers.filterDeleted
import org.seqra.ir.impl.storage.ers.filterLocations
import org.seqra.ir.impl.storage.ers.toClassSource
import org.seqra.ir.impl.storage.execute
import org.seqra.ir.impl.storage.jooq.tables.references.CLASSES
import org.seqra.ir.impl.storage.jooq.tables.references.CLASSHIERARCHIES
import org.seqra.ir.impl.storage.jooq.tables.references.SYMBOLS
import org.seqra.ir.impl.storage.toStorageContext
import org.seqra.ir.impl.storage.txn
import org.seqra.ir.impl.storage.withoutAutoCommit
import org.seqra.ir.impl.util.Sequence
import org.jooq.impl.DSL
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

typealias InMemoryHierarchyCache = ConcurrentHashMap<Long, ConcurrentHashMap<Long, MutableSet<Long>>>

private val objectJvmName = Type.getInternalName(Any::class.java)

class InMemoryHierarchyIndexer(
    persistence: JIRDatabasePersistence,
    private val location: RegisteredLocation,
    private val hierarchy: InMemoryHierarchyCache
) : ByteCodeIndexer {

    private val interner = persistence.symbolInterner

    override fun index(classNode: ClassNode) {
        val clazzSymbolId = classNode.name.className.asSymbolId(interner)
        val superName = classNode.superName
        val superclasses = when {
            superName != null && superName != objectJvmName -> classNode.interfaces + superName
            else -> classNode.interfaces
        }
        superclasses.map { it.className.asSymbolId(interner) }
            .forEach {
                hierarchy.getOrPut(it) { ConcurrentHashMap() }
                    .getOrPut(location.id) { ConcurrentHashMap.newKeySet() }
                    .add(clazzSymbolId)
            }
    }

    override fun flush(context: StorageContext) {
        context.execute(
            sqlAction = {
                val jooq = context.dslContext
                jooq.withoutAutoCommit { conn ->
                    interner.flush(toStorageContext(jooq, conn))
                }
            },
            noSqlAction = {
                interner.flush(context)
            }
        )
    }
}

data class InMemoryHierarchyReq(val name: String, val allHierarchy: Boolean = true, val full: Boolean = false)

class InMemoryHierarchy : JIRFeature<InMemoryHierarchyReq, ClassSource> {
    private val cache = InMemoryHierarchyCache()

    override fun onSignal(signal: JIRSignal) {
        val hierarchy = signal.jIRdb.findInMemoryHierarchy()
            ?: error("InMemoryHierarchy not installed")

        when (signal) {
            is JIRSignal.BeforeIndexing -> {
                signal.jIRdb.persistence.read { context ->
                    val cache = hierarchy.cache
                    val result = mutableListOf<Triple<Long?, Long?, Long?>>()
                    context.execute(
                        sqlAction = {
                            context.dslContext.select(CLASSES.NAME, CLASSHIERARCHIES.SUPER_ID, CLASSES.LOCATION_ID)
                                .from(CLASSHIERARCHIES)
                                .join(CLASSES).on(CLASSHIERARCHIES.CLASS_ID.eq(CLASSES.ID))
                                .fetch().forEach { (classSymbolId, superClassId, locationId) ->
                                    result += (Triple(classSymbolId, superClassId, locationId))
                                }
                        },
                        noSqlAction = {
                            context.txn.all("Class").filterDeleted().forEach { clazz ->
                                val locationId: Long? = clazz.getCompressed("locationId")
                                val classSymbolId: Long? = clazz.getCompressed("nameId")
                                val superClasses = mutableListOf<Long>()
                                clazz.getCompressed<Long>("inherits")?.let { nameId -> superClasses += nameId }
                                links(clazz, "implements").asIterable.forEach { anInterface ->
                                    anInterface.getCompressed<Long>("nameId")?.let { nameId -> superClasses += nameId }
                                }
                                superClasses.forEach { nameId ->
                                    result += (Triple(classSymbolId, nameId, locationId))
                                }
                            }
                        }
                    )
                    result.forEach { (classSymbolId, superClassId, locationId) ->
                        cache.getOrPut(superClassId!!) { ConcurrentHashMap() }
                            .getOrPut(locationId!!) { ConcurrentHashMap.newKeySet() }
                            .add(classSymbolId!!)
                    }
                }
            }

            is JIRSignal.LocationRemoved -> {
                signal.jIRdb.persistence.write {
                    val id = signal.location.id
                    hierarchy.cache.values.forEach {
                        it.remove(id)
                    }
                }
            }

            is JIRSignal.Drop,
            is JIRSignal.Closed -> {
                hierarchy.cache.clear()
            }

            else -> Unit
        }
    }

    override suspend fun query(classpath: JIRClasspath, req: InMemoryHierarchyReq): Sequence<ClassSource> {
        return syncQuery(classpath, req)
    }

    fun syncQuery(classpath: JIRClasspath, req: InMemoryHierarchyReq): Sequence<ClassSource> {
        val persistence = classpath.db.persistence
        if (req.name == JAVA_OBJECT) {
            return persistence.read { classpath.allClassesExceptObject(it, !req.allHierarchy) }
        }
        val hierarchy = classpath.db.findInMemoryHierarchy()?.cache ?: return emptySequence()

        fun getSubclasses(
            symbolId: Long,
            locationIds: Set<Long>,
            transitive: Boolean,
            result: HashSet<Long>
        ) {
            val subclasses = hierarchy[symbolId]?.entries?.flatMap {
                when {
                    locationIds.contains(it.key) -> it.value
                    else -> emptyList()
                }
            }.orEmpty().toSet()
            if (subclasses.isNotEmpty()) {
                result.addAll(subclasses)
            }
            if (transitive) {
                subclasses.forEach {
                    getSubclasses(it, locationIds, true, result)
                }
            }
        }

        val locationIds = classpath.registeredLocationIds
        val classSymbolId = persistence.findSymbolId(req.name)

        val allSubclasses = hashSetOf<Long>()
        getSubclasses(classSymbolId, locationIds, req.allHierarchy, allSubclasses)
        if (allSubclasses.isEmpty()) {
            return emptySequence()
        }
        return Sequence {
            persistence.read { context ->
                context.execute(
                    sqlAction = {
                        val allIds = allSubclasses.toList()
                        BatchedSequence<ClassSource>(defaultBatchSize) { offset, batchSize ->
                            val index = offset ?: 0
                            val ids = allIds.subList(index.toInt(), min(allIds.size, index.toInt() + batchSize))
                            if (ids.isEmpty()) {
                                emptyList()
                            } else {
                                context.dslContext.select(
                                    SYMBOLS.NAME, CLASSES.ID, CLASSES.LOCATION_ID, when {
                                        req.full -> CLASSES.BYTECODE
                                        else -> DSL.inline(ByteArray(0)).`as`(CLASSES.BYTECODE)
                                    }
                                ).from(CLASSES)
                                    .join(SYMBOLS).on(SYMBOLS.ID.eq(CLASSES.NAME))
                                    .where(SYMBOLS.ID.`in`(ids).and(CLASSES.LOCATION_ID.`in`(locationIds)))
                                    .fetch()
                                    .mapNotNull { (className, classId, locationId, byteCode) ->
                                        val source = PersistenceClassSource(
                                            db = classpath.db,
                                            classId = classId!!,
                                            className = className!!,
                                            locationId = locationId!!
                                        ).let {
                                            it.bind(byteCode.takeIf { req.full })
                                        }
                                        (batchSize + index) to source
                                    }
                            }
                        }
                    },
                    noSqlAction = {
                        allSubclasses.asSequence()
                            .flatMap { classNameId ->
                                context.txn.find("Class", "nameId", classNameId.compressed)
                                    .filterLocations(locationIds)
                                    .filterDeleted()
                            }
                            .map { clazz ->
                                val nameId = clazz.getCompressed<Long>("nameId")!!
                                val classId: Long = clazz.id.instanceId
                                clazz.toClassSource(
                                    persistence = persistence,
                                    className = persistence.findSymbolName(nameId),
                                    nameId = nameId,
                                    cachedByteCode = if (req.full) persistence.findBytecode(classId) else null
                                )
                            }
                    }
                )
                    // Eager evaluation is needed, because all computations must be done within current transaction,
                    // i.e. ERS can't be used outside `persistence.read { ... }`, when sequence is actually iterated
                    .toList()
            }
        }
    }

    override fun newIndexer(jIRdb: JIRDatabase, location: RegisteredLocation): ByteCodeIndexer {
        val hierarchy = jIRdb.findInMemoryHierarchy()
            ?: error("InMemoryHierarchy not installed")

        return InMemoryHierarchyIndexer(jIRdb.persistence, location, hierarchy.cache)
    }

}

internal fun JIRClasspath.findSubclassesInMemory(
    name: String,
    allHierarchy: Boolean,
    full: Boolean
): Sequence<JIRClassOrInterface> {
    val hierarchy = db.findInMemoryHierarchy()
        ?: error("InMemoryHierarchy not installed")

    return hierarchy.syncQuery(this, InMemoryHierarchyReq(name, allHierarchy, full)).map {
        toJIRClass(it)
    }
}

fun JIRDatabase.findInMemoryHierarchy(): InMemoryHierarchy? =
    features.filterIsInstance<InMemoryHierarchy>().firstOrNull()
