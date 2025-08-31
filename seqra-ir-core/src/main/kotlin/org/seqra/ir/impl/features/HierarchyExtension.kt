
@file:JvmName("JIRHierarchies")
@file:Suppress("SqlResolve", "SqlSourceToSinkFlow")
package org.seqra.ir.impl.features

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.future
import org.seqra.ir.api.jvm.ClassSource
import org.seqra.ir.api.jvm.JIRClassOrInterface
import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.ext.HierarchyExtension
import org.seqra.ir.api.jvm.ext.JAVA_OBJECT
import org.seqra.ir.api.jvm.ext.findDeclaredMethodOrNull
import org.seqra.ir.api.storage.StorageContext
import org.seqra.ir.api.storage.ers.CollectionEntityIterable
import org.seqra.ir.api.storage.ers.Entity
import org.seqra.ir.api.storage.ers.EntityIterable
import org.seqra.ir.api.storage.ers.Transaction
import org.seqra.ir.api.storage.ers.compressed
import org.seqra.ir.impl.fs.PersistenceClassSource
import org.seqra.ir.impl.storage.BatchedSequence
import org.seqra.ir.impl.storage.defaultBatchSize
import org.seqra.ir.impl.storage.dslContext
import org.seqra.ir.impl.storage.ers.filterDeleted
import org.seqra.ir.impl.storage.ers.filterLocations
import org.seqra.ir.impl.storage.ers.toClassSourceSequence
import org.seqra.ir.impl.storage.execute
import org.seqra.ir.impl.storage.isSqlContext
import org.seqra.ir.impl.storage.jooq.tables.references.CLASSES
import org.seqra.ir.impl.storage.jooq.tables.references.CLASSHIERARCHIES
import org.seqra.ir.impl.storage.jooq.tables.references.SYMBOLS
import org.seqra.ir.impl.storage.txn
import org.seqra.ir.impl.util.Sequence
import org.jooq.Condition
import org.jooq.Record3
import org.jooq.SelectConditionStep
import org.jooq.impl.DSL
import java.util.concurrent.Future

suspend fun JIRClasspath.hierarchyExt(): HierarchyExtension {
    db.awaitBackgroundJobs()
    val isSqlDb = db.persistence.read { context ->
        context.isSqlContext
    }
    return if (isSqlDb) HierarchyExtensionSQL(this) else HierarchyExtensionERS(this)
}

fun JIRClasspath.asyncHierarchyExt(): Future<HierarchyExtension> = GlobalScope.future { hierarchyExt() }

internal fun JIRClasspath.allClassesExceptObject(context: StorageContext, direct: Boolean): Sequence<ClassSource> {
    val locationIds = registeredLocationIds
    return context.execute(
        sqlAction = {
            val jooq = context.dslContext
            if (direct) {
                BatchedSequence(defaultBatchSize) { offset, batchSize ->
                    jooq.select(CLASSES.ID, SYMBOLS.NAME, CLASSES.LOCATION_ID)
                        .from(CLASSES)
                        .join(SYMBOLS).on(SYMBOLS.ID.eq(CLASSES.NAME))
                        .where(
                            locationIds.greaterThan(offset).and(
                                DSL.notExists(
                                    jooq.select(CLASSHIERARCHIES.ID).from(CLASSHIERARCHIES)
                                        .where(
                                            CLASSHIERARCHIES.CLASS_ID.eq(CLASSES.ID)
                                                .and(CLASSHIERARCHIES.IS_CLASS_REF.eq(true))
                                        )
                                )
                            ).and(SYMBOLS.NAME.notEqual(JAVA_OBJECT))
                        )
                        .batchingProcess(this, batchSize)
                }
            } else {
                BatchedSequence(defaultBatchSize) { offset, batchSize ->
                    jooq.select(CLASSES.ID, SYMBOLS.NAME, CLASSES.LOCATION_ID)
                        .from(CLASSES)
                        .join(SYMBOLS).on(SYMBOLS.ID.eq(CLASSES.NAME))
                        .where(locationIds.greaterThan(offset).and(SYMBOLS.NAME.notEqual(JAVA_OBJECT)))
                        .batchingProcess(this, batchSize)
                }
            }
        },
        noSqlAction = {
            val objectNameId by lazy(LazyThreadSafetyMode.NONE) { db.persistence.findSymbolId(JAVA_OBJECT) }
            context.txn.all("Class")
                .filterLocations(locationIds)
                .filterDeleted()
                .filter { clazz ->
                    (!direct || clazz.getCompressed<Long>("inherits") == null) &&
                            clazz.getCompressed<Long>("nameId") != objectNameId
                }.toClassSourceSequence(db).toList().asSequence()
        }
    )
}

private abstract class HierarchyExtensionBase(protected val cp: JIRClasspath) : HierarchyExtension {

    override fun findSubClasses(
        name: String,
        entireHierarchy: Boolean,
        includeOwn: Boolean
    ): Sequence<JIRClassOrInterface> {
        return findSubClasses(cp.findClassOrNull(name) ?: return emptySequence(), entireHierarchy, includeOwn)
    }

    override fun findSubClasses(
        jIRClass: JIRClassOrInterface,
        entireHierarchy: Boolean,
        includeOwn: Boolean
    ): Sequence<JIRClassOrInterface> {
        return when {
            jIRClass.isFinal -> emptySequence()
            else -> explicitSubClasses(jIRClass, entireHierarchy, false)
        }.appendOwn(jIRClass, includeOwn)
    }

    override fun findOverrides(jIRMethod: JIRMethod, includeAbstract: Boolean): Sequence<JIRMethod> {
        if (jIRMethod.isFinal || jIRMethod.isConstructor || jIRMethod.isStatic || jIRMethod.isClassInitializer) {
            return emptySequence()
        }
        val desc = jIRMethod.description
        val name = jIRMethod.name
        return explicitSubClasses(jIRMethod.enclosingClass, entireHierarchy = true, true)
            .mapNotNull { it.findDeclaredMethodOrNull(name, desc) }
            .filter { !it.isPrivate }
    }

    protected abstract fun explicitSubClasses(
        jIRClass: JIRClassOrInterface,
        entireHierarchy: Boolean,
        full: Boolean
    ): Sequence<JIRClassOrInterface>
}

private class HierarchyExtensionERS(cp: JIRClasspath) : HierarchyExtensionBase(cp) {
    override fun explicitSubClasses(
        jIRClass: JIRClassOrInterface,
        entireHierarchy: Boolean,
        full: Boolean
    ): Sequence<JIRClassOrInterface> {
        val name = jIRClass.name
        val db = cp.db
        if (db.findInMemoryHierarchy() != null) {
            return cp.findSubclassesInMemory(name, entireHierarchy, full)
        }
        return Sequence {
            with(db.persistence) {
                read { context ->
                    val txn = context.txn
                    if (name == JAVA_OBJECT) {
                        cp.allClassesExceptObject(context, !entireHierarchy)
                    } else {
                        val locationIds = cp.registeredLocationIds
                        val nameId = name.asSymbolId()
                        if (entireHierarchy) {
                            entireHierarchy(txn, nameId, mutableSetOf())
                        } else {
                            directSubClasses(txn, nameId)
                        }.filter { clazz -> clazz.getCompressed<Long>("locationId") in locationIds }
                            .toClassSourceSequence(db)
                    }.mapTo(mutableListOf()) { cp.toJIRClass(it) }
                }
            }
        }
    }

    private fun entireHierarchy(txn: Transaction, nameId: Long, result: MutableSet<Entity>): EntityIterable {
        val subClasses = directSubClasses(txn, nameId)
        if (subClasses.isNotEmpty) {
            result += subClasses
            subClasses.forEach { clazz ->
                entireHierarchy(txn, clazz.getCompressed<Long>("nameId")!!, result)
            }
        }
        return CollectionEntityIterable(result)
    }

    private fun directSubClasses(txn: Transaction, nameId: Long): EntityIterable {
        val nameIdCompressed = nameId.compressed
        txn.find("Interface", "nameId", nameIdCompressed).firstOrNull()?.let { i ->
            return i.getLinks("implementedBy")
        }
        return txn.find("Class", "inherits", nameIdCompressed)
    }
}

private class HierarchyExtensionSQL(cp: JIRClasspath) : HierarchyExtensionBase(cp) {

    companion object {
        fun entireHierarchyQuery(locationIds: String, sinceId: Long?) = """
            WITH RECURSIVE Hierarchy(class_name_id, class_id) AS (
                SELECT Classes.name, ClassHierarchies.class_id FROM ClassHierarchies
                    JOIN Symbols ON Symbols.id = ClassHierarchies.super_id
                    JOIN Classes ON Classes.id = ClassHierarchies.class_id
                    WHERE Symbols.name = ?
                UNION ALL
                SELECT Classes.name, ClassHierarchies.class_id FROM ClassHierarchies
                    JOIN Classes ON Classes.id = ClassHierarchies.class_id
                    JOIN Hierarchy ON Hierarchy.class_name_id = ClassHierarchies.super_id)
            SELECT DISTINCT Classes.id, Classes.location_id,  Symbols.name as name_name, Classes.bytecode from Hierarchy
                JOIN Classes ON Classes.id = hierarchy.class_id
                JOIN Symbols ON Symbols.id = Classes.name
             WHERE location_id in ($locationIds) and ($sinceId is null or Hierarchy.class_id > $sinceId)
             ORDER BY Classes.id
        """.trimIndent()

        fun directSubClassesQuery(locationIds: String, sinceId: Long?) = """
            SELECT Classes.id, Classes.location_id, SymbolsName.name as name_name, Classes.bytecode FROM ClassHierarchies
                JOIN Symbols ON Symbols.id = ClassHierarchies.super_id
                JOIN Symbols as SymbolsName ON SymbolsName.id = Classes.name
                JOIN Classes ON Classes.id = ClassHierarchies.class_id
            WHERE Symbols.name = ? and ($sinceId is null or ClassHierarchies.class_id > $sinceId) AND Classes.location_id in ($locationIds)
            ORDER BY Classes.id
        """.trimIndent()

    }

    override fun explicitSubClasses(
        jIRClass: JIRClassOrInterface,
        entireHierarchy: Boolean,
        full: Boolean
    ): Sequence<JIRClassOrInterface> {
        val name = jIRClass.name
        if (cp.db.findInMemoryHierarchy() != null) {
            return cp.findSubclassesInMemory(name, entireHierarchy, full)
        }
        return cp.subClasses(name, entireHierarchy).map { cp.toJIRClass(it) }
    }
}

private fun Sequence<JIRClassOrInterface>.appendOwn(
    root: JIRClassOrInterface,
    includeOwn: Boolean
): Sequence<JIRClassOrInterface> {
    return if (includeOwn) sequenceOf(root) + this else this
}

private fun JIRClasspath.subClasses(name: String, entireHierarchy: Boolean): Sequence<ClassSource> {
    return db.persistence.read { context ->
        if (name == JAVA_OBJECT) {
            allClassesExceptObject(context, !entireHierarchy)
        } else {
            val locationIds = registeredLocationIds.joinToString(", ") { it.toString() }
            val dslContext = context.dslContext
            BatchedSequence(defaultBatchSize) { offset, batchSize ->
                val query = when {
                    entireHierarchy -> HierarchyExtensionSQL.entireHierarchyQuery(locationIds, offset)
                    else -> HierarchyExtensionSQL.directSubClassesQuery(locationIds, offset)
                }
                val cursor = dslContext.fetchLazy(query, name)
                cursor.fetchNext(batchSize).map { record ->
                    val id = record.get(CLASSES.ID)!!
                    id to PersistenceClassSource(
                        db = db,
                        classId = record.get(CLASSES.ID)!!,
                        className = record.get("name_name") as String,
                        locationId = record.get(CLASSES.LOCATION_ID)!!
                    ).bind(record.get(CLASSES.BYTECODE))
                }.also {
                    cursor.close()
                }
            }
        }
    }
}

private fun SelectConditionStep<Record3<Long?, String?, Long?>>.batchingProcess(
    cp: JIRClasspath,
    batchSize: Int
): List<Pair<Long, PersistenceClassSource>> {
    return orderBy(CLASSES.ID)
        .limit(batchSize)
        .fetch()
        .mapNotNull { (classId, className, locationId) ->
            classId!! to PersistenceClassSource(
                db = cp.db,
                classId = classId,
                className = className!!,
                locationId = locationId!!
            )
        }
}

private fun Collection<Long>.greaterThan(offset: Long?): Condition {
    return when (offset) {
        null -> CLASSES.LOCATION_ID.`in`(this)
        else -> CLASSES.LOCATION_ID.`in`(this).and(CLASSES.ID.greaterThan(offset))
    }
}