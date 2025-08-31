package org.seqra.ir.approximation

import org.seqra.ir.api.jvm.ByteCodeIndexer
import org.seqra.ir.api.jvm.JIRClassExtFeature
import org.seqra.ir.api.jvm.JIRClassOrInterface
import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.JIRDatabase
import org.seqra.ir.api.jvm.JIRDatabasePersistence
import org.seqra.ir.api.jvm.JIRFeature
import org.seqra.ir.api.jvm.JIRField
import org.seqra.ir.api.jvm.JIRInstExtFeature
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.JIRSignal
import org.seqra.ir.api.jvm.RegisteredLocation
import org.seqra.ir.api.jvm.cfg.JIRInstList
import org.seqra.ir.api.jvm.cfg.JIRRawInst
import org.seqra.ir.api.storage.StorageContext
import org.seqra.ir.api.storage.ers.Entity
import org.seqra.ir.api.storage.ers.EntityIterable
import org.seqra.ir.api.storage.ers.compressed
import org.seqra.ir.approximation.annotation.Approximate
import org.seqra.ir.approximation.annotation.Version
import org.seqra.ir.impl.cfg.JIRInstListImpl
import org.seqra.ir.impl.fs.className
import org.seqra.ir.impl.storage.execute
import org.seqra.ir.impl.storage.txn
import org.seqra.ir.impl.types.RefKind
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

// TODO we need somehow to remove approximation classes from the hierarchy to avoid presence of them in the inheritors of Object

/**
 *  A feature building mapping between so-called approximations and their targets.
 *  Other features like [ClassContentApproximationFeature] and [ApproximationsInstructionsFeature]
 *  uses it for modifying source code of target classes with information from
 *  the corresponding approximations.
 *
 *  Note: for correct work of the feature, you must wait
 *  till the whole processing of classpath is finished,
 *  otherwise you might get incomplete mapping.
 *  See [JIRDatabase.awaitBackgroundJobs].
 */
class Approximations(
    versions: List<VersionInfo>
) : JIRFeature<Any?, Any?>, JIRClassExtFeature, JIRInstExtFeature {

    private val instSubstitutorForApproximations = InstSubstitutorForApproximations(this)
    private val transformerIntoVirtual = TransformerIntoVirtual(this)

    private val originalToApproximation: ConcurrentMap<OriginalClassName, ApproximationClassName> = ConcurrentHashMap()
    private val approximationToOriginal: ConcurrentMap<ApproximationClassName, OriginalClassName> = ConcurrentHashMap()

    private val versionMap: VersionMap = versions.associate { it.target to it.version }

    override suspend fun query(classpath: JIRClasspath, req: Any?): Sequence<Any?> {
        // returns an empty sequence for now, all requests are made using
        // findApproximationOrNull and findOriginalByApproximation functions
        return emptySequence()
    }

    override fun newIndexer(
        jIRdb: JIRDatabase,
        location: RegisteredLocation
    ): ByteCodeIndexer = ApproximationIndexer(originalToApproximation, approximationToOriginal, versionMap)

    private val Entity.annotationNameId: Long?
        get() = getCompressed<Long>("nameId")

    private val Entity.annotationValueNameId: Long?
        get() = getCompressedBlob<Long>("nameId")

    private fun EntityIterable.annotationPrimitiveValueByName(valueNameId: Long): Long? {
        val value = find { valueNameId == it.getCompressedBlob<Long>("nameId") }
        return value?.getCompressedBlob<Long>("primitiveValue")
    }

    private fun EntityIterable.annotationStringValueByName(
        persistence: JIRDatabasePersistence,
        valueNameId: Long
    ): String? {
        val valueId = annotationPrimitiveValueByName(valueNameId)
        if (valueId != null)
            return persistence.findSymbolName(valueId)

        return null
    }

    override fun onSignal(signal: JIRSignal) {
        if (signal is JIRSignal.BeforeIndexing) {
            val persistence = signal.jIRdb.persistence
            val approxSymbol = persistence.findSymbolId(approximationAnnotationClassName)
            persistence.read { context ->
                context.execute(
                    sqlAction = {
                        TODO("support versions for SQL persistence")
//                        context.dslContext.select(CLASSES.NAME, ANNOTATIONVALUES.CLASS_SYMBOL)
//                            .from(ANNOTATIONS)
//                            .join(CLASSES).on(ANNOTATIONS.CLASS_ID.eq(CLASSES.ID))
//                            .join(ANNOTATIONVALUES).on(ANNOTATIONVALUES.ANNOTATION_ID.eq(ANNOTATIONS.ID))
//                            .where(
//                                ANNOTATIONS.ANNOTATION_NAME.eq(approxSymbol).and(
//                                    ANNOTATIONVALUES.NAME.eq("value")
//                                )
//                            )
//                            .fetch().asSequence().map { record -> record.value1() to record.value2() }
                    },
                    noSqlAction = {
                        val valueId = persistence.findSymbolId("value")
                        val versionsId = persistence.findSymbolId("versions")
                        val versionSymbol = persistence.findSymbolId(versionAnnotationClassName)
                        val targetId = persistence.findSymbolId("target")
                        val fromVersionId = persistence.findSymbolId("fromVersion")
                        val toVersionId = persistence.findSymbolId("toVersion")
                        context.txn.find("Annotation", "nameId", approxSymbol.compressed)
                            .filter { it.getCompressedBlob<Int>("refKind") == RefKind.CLASS.ordinal }
                            .mapNotNull { annotation ->
                                val values = annotation.getLinks("values")
                                val versionsValue = values.filterTo(mutableListOf()) {
                                    versionsId == it.annotationValueNameId
                                }
                                if (versionsValue.isEmpty())
                                    return@mapNotNull annotation to values

                                val versionMatches = versionsValue.any { versionValue ->
                                    val versionAnnotation = versionValue.getLink("refAnnotation")
                                    check(versionSymbol == versionAnnotation.annotationNameId)
                                    val versionValues = versionAnnotation.getLinks("values")
                                    val target = versionValues.annotationStringValueByName(persistence, targetId)
                                        ?: error("unable to find `target` value in `Version` annotation")
                                    val fromVersion = versionValues.annotationStringValueByName(persistence, fromVersionId)
                                        ?: error("unable to find `fromVersion` value in `Version` annotation")
                                    val toVersion = versionValues.annotationStringValueByName(persistence, toVersionId)
                                        ?: error("unable to find `toVersion` value in `Version` annotation")
                                    VersionsIntervalInfo(target, fromVersion, toVersion).matches(versionMap)
                                }
                                if (versionMatches)
                                    annotation to values
                                else null
                            }.flatMap { (annotation, values) ->
                                annotation.getLink("ref").let { clazz ->
                                    values.map { clazz to it }
                                }
                            }.filter { (_, annotationValue) ->
                                valueId == annotationValue["nameId"]
                            }.map { (clazz, annotationValue) ->
                                clazz.getCompressed<Long>("nameId") to annotationValue.getCompressedBlob<Long>("classSymbolId")
                            }
                    }
                ).forEach { (approximation, original) ->
                    val approximationClassName = persistence.findSymbolName(approximation!!).toApproximationName()
                    val originalClassName = persistence.findSymbolName(original!!).toOriginalName()
                    originalToApproximation[originalClassName] = approximationClassName
                    approximationToOriginal[approximationClassName] = originalClassName
                }
            }
        }
    }


    /**
     * Returns a list of [JIREnrichedVirtualField] if there is an approximation for [clazz] and null otherwise.
     */
    override fun fieldsOf(clazz: JIRClassOrInterface): List<JIRField>? {
        val approximationName = findApproximationByOriginOrNull(clazz.name.toOriginalName()) ?: return null
        val approximationClass = clazz.classpath.findClassOrNull(approximationName) ?: return null

        return approximationClass.declaredFields.map { transformerIntoVirtual.transformIntoVirtualField(clazz, it) }
    }

    /**
     * Returns a list of [JIREnrichedVirtualMethod] if there is an approximation for [clazz] and null otherwise.
     */
    override fun methodsOf(clazz: JIRClassOrInterface): List<JIRMethod>? = with(transformerIntoVirtual) {
        val approximationName = findApproximationByOriginOrNull(clazz.name.toOriginalName()) ?: return null
        val approximationClass = clazz.classpath.findClassOrNull(approximationName) ?: return null

        return approximationClass.declaredMethods.map {
            approximationClass.classpath.transformMethodIntoVirtual(clazz, it)
        }
    }

    override fun transformRawInstList(method: JIRMethod, list: JIRInstList<JIRRawInst>): JIRInstList<JIRRawInst> {
        return JIRInstListImpl(list.map { it.accept(instSubstitutorForApproximations) })
    }

    /**
     * Returns a name of the approximation class for [className] if it exists and null otherwise.
     */
    fun findApproximationByOriginOrNull(
        className: OriginalClassName
    ): String? = originalToApproximation[className]?.className

    /**
     * Returns a name of the target class for [className] approximation if it exists and null otherwise.
     */
    fun findOriginalByApproximationOrNull(
        className: ApproximationClassName
    ): String? = approximationToOriginal[className]?.className
}

data class VersionInfo(
    val target: String,
    val version: String,
) {
    init {
        check(version.isVersion)
    }
}

private typealias VersionMap = Map<String, String>

private data class VersionsIntervalInfo(
    val target: String,
    val fromVersion: String,
    val toVersion: String,
) {
    init {
        check(fromVersion.isVersion)
        check(toVersion.isVersion)
    }

    fun matches(versionMap: VersionMap): Boolean {
        check(fromVersion.isVersion && toVersion.isVersion)

        val version = versionMap[this.target] ?: return false

        val fromVersionNumbers = fromVersion.toNumbers
        val toVersionNumbers = toVersion.toNumbers
        val versionNumbers = version.toNumbers

        return versionNumbers.isVersionInRange(fromVersionNumbers, toVersionNumbers)
    }
}

/**
 * Checks if the string is a version in the form of 3 numbers separated by dots, e.g., "1.2.3".
 */
private val String.isVersion: Boolean get() =
    Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matches(this)

private val String.toNumbers: IntArray get() {
    val numbers = this.split('.')
    check(numbers.size == 3)
    return IntArray(numbers.size) { i -> numbers[i].toInt() }
}

/**
 * Checks if a version (array of 3 numbers) is within the inclusive range defined by fromVersion and toVersion (also arrays of 3 numbers).
 * Example: 1.1.1 <= 1.2.0 <= 2.0.0
 */
private fun IntArray.isVersionInRange(fromVersion: IntArray, toVersion: IntArray): Boolean {
    require(this.size == 3 && fromVersion.size == 3 && toVersion.size == 3) { "All version arrays must have size 3" }
    for (i in 0..2) {
        if (this[i] < fromVersion[i]) return false
        if (this[i] > fromVersion[i]) break
    }
    for (i in 0..2) {
        if (this[i] > toVersion[i]) return false
        if (this[i] < toVersion[i]) break
    }
    return true
}

private class ApproximationIndexer(
    private val originalToApproximation: ConcurrentMap<OriginalClassName, ApproximationClassName>,
    private val approximationToOriginal: ConcurrentMap<ApproximationClassName, OriginalClassName>,
    private val versionMap: VersionMap
) : ByteCodeIndexer {

    private fun annotationValueByName(versionValues: List<Any>, name: String): Any? {
        val valueNameIdx = versionValues.indexOf(name)
        if (valueNameIdx == -1)
            return null

        val valueIdx = valueNameIdx + 1
        if (valueIdx >= versionValues.size)
            return null

        return versionValues[valueIdx]
    }

    private fun annotationStringValueByName(versionValues: List<Any>, name: String): String? {
        return annotationValueByName(versionValues, name) as? String
    }

    private val AnnotationNode.versionIntervalInfo: VersionsIntervalInfo get() {
        val target = annotationStringValueByName(values, "target")
            ?: error("unable to find `target` value in `Version` annotation")
        val from = annotationStringValueByName(values, "fromVersion")
            ?: error("unable to find `target` value in `Version` annotation")
        val to = annotationStringValueByName(values, "toVersion")
            ?: error("unable to find `target` value in `Version` annotation")
        return VersionsIntervalInfo(target, from, to)
    }

    private fun checkVersion(approximationAnnotation: AnnotationNode): Boolean {
        val versions = annotationValueByName(approximationAnnotation.values, "versions") as? List<*>
            // When `Approximate` annotation does not contain `Version` annotation, it matches any version
            ?: return true

        if (versions.isEmpty())
            return true

        for (version in versions) {
            version as AnnotationNode
            if (version.versionIntervalInfo.matches(versionMap))
                return true
        }

        return false
    }

    override fun index(classNode: ClassNode) {
        val annotations = classNode.visibleAnnotations ?: return

        // Check whether the classNode contains an approximation related annotation
        val approximationAnnotation = annotations.singleOrNull {
            approximationAnnotationClassName in it.desc.className
        } ?: return

        if (!checkVersion(approximationAnnotation))
            return

        // Extract a name of the target class for this approximation
        val target = approximationAnnotation.values.filterIsInstance<org.objectweb.asm.Type>().single()

        val originalClassName = target.className.toOriginalName()
        val approximationClassName = classNode.name.className.toApproximationName()

        // Ensure that each approximation has one and only one
        require(originalToApproximation.getOrDefault(originalClassName, approximationClassName) == approximationClassName) {
            "An error occurred during approximations indexing: you tried to add `$approximationClassName` " +
                    "as an approximation for `$originalClassName`, but the target class is already " +
                    "associated with approximation `${originalToApproximation[originalClassName]}`. " +
                    "Only bijection between classes is allowed."
        }
        require(approximationToOriginal.getOrDefault(approximationClassName, originalClassName) == originalClassName) {
            "An error occurred during approximations indexing: you tried to add `$approximationClassName` " +
                    "as an approximation for `$originalClassName`, but this approximation is already used for " +
                    "`${approximationToOriginal[approximationClassName]}`. " +
                    "Only bijection between classes is allowed."
        }

        originalToApproximation[originalClassName] = approximationClassName
        approximationToOriginal[approximationClassName] = originalClassName
    }

    override fun flush(context: StorageContext) {
        // do nothing
    }
}

private val approximationAnnotationClassName = Approximate::class.qualifiedName!!
private val versionAnnotationClassName = Version::class.qualifiedName!!

@JvmInline
value class ApproximationClassName(val className: String) {
    override fun toString(): String = className
}

@JvmInline
value class OriginalClassName(val className: String) {
    override fun toString(): String = className
}
