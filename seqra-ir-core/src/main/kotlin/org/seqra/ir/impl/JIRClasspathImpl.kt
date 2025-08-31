package org.seqra.ir.impl

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import org.seqra.ir.api.jvm.ClassSource
import org.seqra.ir.api.jvm.JIRAnnotation
import org.seqra.ir.api.jvm.JIRArrayType
import org.seqra.ir.api.jvm.JIRByteCodeLocation
import org.seqra.ir.api.jvm.JIRClassOrInterface
import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.JIRClasspathExtFeature
import org.seqra.ir.api.jvm.JIRClasspathExtFeature.JIRResolvedClassResult
import org.seqra.ir.api.jvm.JIRClasspathExtFeature.JIRResolvedTypeResult
import org.seqra.ir.api.jvm.JIRClasspathFeature
import org.seqra.ir.api.jvm.JIRClasspathTask
import org.seqra.ir.api.jvm.JIRFeatureEvent
import org.seqra.ir.api.jvm.JIRRefType
import org.seqra.ir.api.jvm.JIRType
import org.seqra.ir.api.jvm.PredefinedPrimitives
import org.seqra.ir.api.jvm.RegisteredLocation
import org.seqra.ir.api.jvm.ext.JAVA_OBJECT
import org.seqra.ir.api.jvm.ext.toType
import org.seqra.ir.impl.bytecode.JIRClassOrInterfaceImpl
import org.seqra.ir.impl.features.JIRFeatureEventImpl
import org.seqra.ir.impl.features.JIRFeaturesChain
import org.seqra.ir.impl.features.classpaths.AbstractJIRResolvedResult.JIRResolvedClassResultImpl
import org.seqra.ir.impl.features.classpaths.AbstractJIRResolvedResult.JIRResolvedTypeResultImpl
import org.seqra.ir.impl.features.classpaths.ClasspathCache
import org.seqra.ir.impl.features.classpaths.JIRUnknownClass
import org.seqra.ir.impl.features.classpaths.UnknownClassMethodsAndFields
import org.seqra.ir.impl.features.classpaths.UnknownClasses
import org.seqra.ir.impl.features.classpaths.isResolveAllToUnknown
import org.seqra.ir.impl.fs.ClassSourceImpl
import org.seqra.ir.impl.types.JIRArrayTypeImpl
import org.seqra.ir.impl.types.JIRClassTypeImpl
import org.seqra.ir.impl.types.substition.JIRSubstitutorImpl
import org.seqra.ir.impl.vfs.ClasspathVfs
import org.seqra.ir.impl.vfs.GlobalClassesVfs
import kotlin.LazyThreadSafetyMode.PUBLICATION

class JIRClasspathImpl(
    private val locationsRegistrySnapshot: LocationsRegistrySnapshot,
    override val db: JIRDatabaseImpl,
    override val features: List<JIRClasspathFeature>,
    globalClassVFS: GlobalClassesVfs
) : JIRClasspath {

    override val locations: List<JIRByteCodeLocation> = locationsRegistrySnapshot.locations.mapNotNull { it.jIRLocation }
    override val registeredLocations: List<RegisteredLocation> = locationsRegistrySnapshot.locations
    override val registeredLocationIds: Set<Long> = locationsRegistrySnapshot.ids
    private val classpathVfs = ClasspathVfs(globalClassVFS, locationsRegistrySnapshot)
    private val featuresChain = JIRFeaturesChain(
        if (!features.any { it is UnknownClasses }) {
            features + JIRClasspathFeatureImpl()
        } else {
            (features.filter { it !is UnknownClasses } + JIRClasspathFeatureImpl() + UnknownClasses).let {
                it.filter { it !is UnknownClassMethodsAndFields } + UnknownClassMethodsAndFields
            }
        })

    override suspend fun refreshed(closeOld: Boolean): JIRClasspath {
        return db.new(this).also {
            if (closeOld) {
                close()
            }
        }
    }

    private val javaObjectClass: JIRClassOrInterface? by lazy(PUBLICATION) {
        findClassWithCache(JAVA_OBJECT)
    }

    override fun findClassOrNull(name: String): JIRClassOrInterface? =
        if (JAVA_OBJECT == name) javaObjectClass else findClassWithCache(name)

    private fun findClassWithCache(name: String): JIRClassOrInterface? =
        featuresChain.call<JIRClasspathExtFeature, JIRResolvedClassResult> {
            it.tryFindClass(this, name)
        }?.clazz

    override fun typeOf(
        jIRClass: JIRClassOrInterface,
        nullability: Boolean?,
        annotations: List<JIRAnnotation>
    ): JIRRefType {
        val jIRRefType = findTypeOrNullWithNullability(jIRClass.name, nullability) as? JIRRefType
        jIRRefType?.let {
            //
            // NB! cached type can have a different set of annotations,e.g., if it has
            // been substituted by a "substitutor"
            //
            val cachedAnnotations = jIRRefType.annotations
            if (cachedAnnotations.size == annotations.size) {
                if (annotations.isEmpty() || cachedAnnotations.toSet() == annotations.toSet()) {
                    return jIRRefType
                }
            }
        }
        return newClassType(jIRClass, nullability, annotations)
    }

    override fun arrayTypeOf(elementType: JIRType, nullability: Boolean?, annotations: List<JIRAnnotation>): JIRArrayType {
        return JIRArrayTypeImpl(elementType, nullability, annotations)
    }

    override fun toJIRClass(source: ClassSource): JIRClassOrInterface {
        // findClassOrNull() can return instance of JIRVirtualClass which is not expected here
        // also a duplicate class with different location can be cached
        return (findCachedClass(source.className) as? JIRClassOrInterfaceImpl)?.run {
            if (source.location.id == declaration.location.id) this else null
        } ?: newClassOrInterface(source)
    }

    private val javaObjectType: JIRType? by lazy(PUBLICATION) {
        findTypeOrNullWithNullability(JAVA_OBJECT)
    }

    override fun findTypeOrNull(name: String): JIRType? =
        if (JAVA_OBJECT == name) javaObjectType else findTypeOrNullWithNullability(name)

    override suspend fun <T : JIRClasspathTask> execute(task: T): T {
        val locations = registeredLocations.filter { task.shouldProcess(it) }
        task.before(this)
        withContext(Dispatchers.IO) {
            val parentScope = this
            locations.map {
                async {
                    val sources = db.persistence.findClassSources(db, it)
                        .takeIf { it.isNotEmpty() } ?: it.jIRLocation?.classes?.map { entry ->
                        ClassSourceImpl(location = it, className = entry.key, byteCode = entry.value)
                    } ?: emptyList()

                    sources.forEach {
                        if (parentScope.isActive && task.shouldProcess(it)) {
                            task.process(it, this@JIRClasspathImpl)
                        }
                    }
                }
            }.joinAll()
        }
        task.after(this)
        return task
    }

    override fun findClasses(name: String): Set<JIRClassOrInterface> {
        return featuresChain.features.filterIsInstance<JIRClasspathExtFeature>().flatMap { feature ->
            feature.findClasses(this, name).orEmpty()
        }.toSet()
    }

    override fun isInstalled(feature: JIRClasspathFeature): Boolean {
        return featuresChain.features.contains(feature)
    }

    override fun close() {
        locationsRegistrySnapshot.close()
    }

    private fun findTypeOrNullWithNullability(name: String, nullable: Boolean? = null): JIRType? {
        return featuresChain.call<JIRClasspathExtFeature, JIRResolvedTypeResult> {
            it.tryFindType(this, name, nullable)
        }?.type
    }

    private fun newClassType(
        jIRClass: JIRClassOrInterface,
        nullability: Boolean?,
        annotations: List<JIRAnnotation>
    ): JIRClassTypeImpl {
        return JIRClassTypeImpl(
            this,
            jIRClass.name,
            jIRClass.outerClass?.toType() as? JIRClassTypeImpl,
            JIRSubstitutorImpl.empty,
            nullability,
            annotations
        )
    }

    private fun newClassOrInterface(source: ClassSource) = JIRClassOrInterfaceImpl(this, source, featuresChain)

    private fun findCachedClass(name: String): JIRClassOrInterface? {
        return featuresChain.call<JIRClasspathExtFeature, JIRResolvedClassResult> { feature ->
            if (feature is ClasspathCache) feature.tryFindClass(this, name) else null
        }?.clazz ?: findClassOrNull(name)
    }

    private inner class JIRClasspathFeatureImpl : JIRClasspathExtFeature {

        override fun tryFindClass(classpath: JIRClasspath, name: String): JIRResolvedClassResult? {
            val source = classpathVfs.firstClassOrNull(name)
            val jIRClass = source?.let { newClassOrInterface(it.source) }
                ?: db.persistence.findClassSourceByName(classpath, name)?.let {
                    newClassOrInterface(it)
                }
            if (jIRClass == null && isResolveAllToUnknown) {
                return null
            }
            return JIRResolvedClassResultImpl(name, jIRClass)
        }

        override fun tryFindType(classpath: JIRClasspath, name: String, nullable: Boolean?): JIRResolvedTypeResult? {
            if (name.endsWith("[]")) {
                val targetName = name.removeSuffix("[]")
                return JIRResolvedTypeResultImpl(name,
                    findTypeOrNull(targetName)?.let { JIRArrayTypeImpl(it, true) }
                )
            }
            val predefined = PredefinedPrimitives.of(name, classpath)
            if (predefined != null) {
                return JIRResolvedTypeResultImpl(name, predefined)
            }
            return when (val clazz = findClassOrNull(name)) {
                null -> JIRResolvedTypeResultImpl(name, null)
                is JIRUnknownClass -> null // delegating to UnknownClass feature
                else -> JIRResolvedTypeResultImpl(name, newClassType(clazz, nullable, clazz.annotations))
            }
        }

        override fun findClasses(classpath: JIRClasspath, name: String): List<JIRClassOrInterface> {
            val findClassNodes = classpathVfs.findClassNodes(name)
            val vfsClasses = findClassNodes.map { toJIRClass(it.source) }
            val persistedClasses = db.persistence.findClassSources(classpath, name).map { toJIRClass(it) }
            return buildSet {
                addAll(vfsClasses)
                addAll(persistedClasses)
            }.toList()
        }

        override fun event(result: Any): JIRFeatureEvent {
            return JIRFeatureEventImpl(this, result)
        }

    }

}
