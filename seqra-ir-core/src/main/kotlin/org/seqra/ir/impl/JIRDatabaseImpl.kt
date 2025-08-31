package org.seqra.ir.impl

import com.google.common.hash.Hashing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.seqra.ir.api.jvm.JavaVersion
import org.seqra.ir.api.jvm.JIRByteCodeLocation
import org.seqra.ir.api.jvm.JIRClasspath
import org.seqra.ir.api.jvm.JIRClasspathFeature
import org.seqra.ir.api.jvm.JIRDatabase
import org.seqra.ir.api.jvm.JIRDatabasePersistence
import org.seqra.ir.api.jvm.JIRFeature
import org.seqra.ir.api.jvm.JIRSettings
import org.seqra.ir.api.jvm.RegisteredLocation
import org.seqra.ir.impl.features.classpaths.ClasspathCache
import org.seqra.ir.impl.features.classpaths.KotlinMetadata
import org.seqra.ir.impl.features.classpaths.MethodInstructionsFeature
import org.seqra.ir.impl.features.classpaths.UnknownClassMethodsAndFields
import org.seqra.ir.impl.features.classpaths.UnknownClasses
import org.seqra.ir.impl.fs.JavaRuntime
import org.seqra.ir.impl.fs.asByteCodeLocation
import org.seqra.ir.impl.fs.filterExisting
import org.seqra.ir.impl.fs.lazySources
import org.seqra.ir.impl.fs.sources
import org.seqra.ir.impl.storage.ers.ERS_DATABASE_PERSISTENCE_SPI
import org.seqra.ir.impl.vfs.GlobalClassesVfs
import org.seqra.ir.impl.vfs.RemoveLocationsVisitor
import java.io.File
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class JIRDatabaseImpl(
    internal val javaRuntime: JavaRuntime,
    private val settings: JIRSettings
) : JIRDatabase {

    override val persistence: JIRDatabasePersistence
    internal val featuresRegistry: FeaturesRegistry
    internal val locationsRegistry: LocationsRegistry

    private val classesVfs = GlobalClassesVfs()
    private val hooks = settings.hooks.map { it(this) }

    private val backgroundJobs = ConcurrentHashMap<Int, Job>()

    @Volatile
    private var isClosed = false
    private val jobId = AtomicInteger()

    private val backgroundScope = BackgroundScope()

    @Volatile
    private var isImmutable = false

    private val featuresHash: BigInteger

    init {
        val persistenceId = (settings.persistenceId ?: ERS_DATABASE_PERSISTENCE_SPI)
        val persistenceSPI = JIRDatabasePersistenceSPI.getProvider(persistenceId)
        persistence = persistenceSPI.newPersistence(javaRuntime, settings)
        featuresRegistry = FeaturesRegistry(settings.features).apply { bind(this@JIRDatabaseImpl) }
        locationsRegistry = persistenceSPI.newLocationsRegistry(this)
        featuresHash = settings.features.fold(BigInteger.ZERO) { result, feature ->
            result xor BigInteger(Hashing.sha256().hashString(feature.javaClass.name, StandardCharsets.UTF_8).asBytes())
        }
    }

    override val id: String
        get() = locations.mapNotNull { it.jIRLocation?.fileSystemIdHash }
            .fold(featuresHash) { result, hash -> result xor hash }.toString(Character.MAX_RADIX)

    override val locations: List<RegisteredLocation> get() = locationsRegistry.actualLocations

    suspend fun restore() {
        persistence.setup()
        locationsRegistry.cleanup()
        val runtime = JavaRuntime(settings.jre).allLocations.takeIf { settings.buildModelForJRE }
        val runtimeNew = runtime?.let { locationsRegistry.setup(it).new }
        val registeredNew = locationsRegistry.registerIfNeeded(
            settings.predefinedDirOrJars.filter { it.exists() }
                .flatMap { it.asByteCodeLocation(javaRuntime.version, isRuntime = false) }.distinct()
        ).new
        if (canBeDumped() && persistence.tryLoad(id)) {
            isImmutable = true
        }
        featuresRegistry.broadcast(JIRInternalSignal.BeforeIndexing(settings.persistenceClearOnStart ?: false))
        if (!isImmutable) {
            runtimeNew?.process(false)
            registeredNew.process(true)
        }
    }

    private fun List<JIRClasspathFeature>?.appendBuiltInFeatures(): List<JIRClasspathFeature> {
        return mutableListOf<JIRClasspathFeature>().also { result ->
            result += orEmpty()
            if (!result.any { it is ClasspathCache }) {
                result += ClasspathCache(settings.cacheSettings)
            }
            result += KotlinMetadata
            result += MethodInstructionsFeature(settings.keepLocalVariableNames)
            if (result.any { it is UnknownClasses } && !result.any { it is UnknownClassMethodsAndFields }) {
                result += UnknownClassMethodsAndFields
            }
        }
    }

    override suspend fun classpath(dirOrJars: List<File>, features: List<JIRClasspathFeature>?): JIRClasspath {
        assertNotClosed()
        val existingLocations =
            dirOrJars.filterExisting().flatMap { it.asByteCodeLocation(javaRuntime.version) }.distinct()
        val processed = locationsRegistry.registerIfNeeded(existingLocations)
            .also { it.new.process(true) }.registered + locationsRegistry.runtimeLocations
        return JIRClasspathImpl(
            locationsRegistry.newSnapshot(processed),
            this,
            features.appendBuiltInFeatures(),
            classesVfs
        )
    }

    fun new(cp: JIRClasspathImpl): JIRClasspath {
        assertNotClosed()
        return JIRClasspathImpl(
            locationsRegistry.newSnapshot(cp.registeredLocations),
            cp.db,
            cp.features,
            classesVfs
        )
    }

    override val runtimeVersion: JavaVersion
        get() = javaRuntime.version

    override suspend fun load(dirOrJar: File) = apply {
        assertNotClosed()
        load(listOf(dirOrJar))
    }

    override suspend fun load(dirOrJars: List<File>) = apply {
        assertNotClosed()
        loadLocations(dirOrJars.filterExisting().flatMap { it.asByteCodeLocation(javaRuntime.version) }.distinct())
    }

    override suspend fun loadLocations(locations: List<JIRByteCodeLocation>) = apply {
        assertNotClosed()
        locationsRegistry.registerIfNeeded(locations).new.process(true)
    }

    override suspend fun refresh() {
        awaitBackgroundJobs()
        locationsRegistry.refresh().new.process(true)
        val result = locationsRegistry.cleanup()
        classesVfs.visit(RemoveLocationsVisitor(result.outdated, settings.byteCodeSettings.prefixes))
    }

    override suspend fun rebuildFeatures() {
        awaitBackgroundJobs()
        featuresRegistry.broadcast(JIRInternalSignal.Drop)

        withContext(Dispatchers.IO) {
            val locations = locationsRegistry.actualLocations
            val parentScope = this
            locations.map {
                async {
                    val addedClasses = persistence.findClassSources(this@JIRDatabaseImpl, it)
                    parentScope.ifActive { featuresRegistry.index(it, addedClasses) }
                }
            }.joinAll()
        }
    }

    override fun watchFileSystemChanges(): JIRDatabase {
        val delay = settings.watchFileSystemDelay?.toLong()
        if (delay != null) { // just paranoid check
            backgroundScope.launch {
                while (true) {
                    delay(delay)
                    refresh()
                }
            }
        }
        return this
    }

    override suspend fun awaitBackgroundJobs() {
        if (!isImmutable) {
            backgroundJobs.values.joinAll()
            if (canBeDumped()) {
                persistence.setImmutable(id)
                isImmutable = true
            }
        }
    }

    override suspend fun cancelBackgroundJobs() {
        backgroundJobs.values.forEach {
            it.cancel()
        }
        awaitBackgroundJobs()
        backgroundJobs.clear()
    }

    override val features: List<JIRFeature<*, *>>
        get() = featuresRegistry.features

    suspend fun afterStart() {
        hooks.forEach { it.afterStart() }
    }

    override suspend fun setImmutable() {
        if (!isImmutable) {
            backgroundJobs.values.joinAll()
            persistence.setImmutable(id)
            isImmutable = true
        }
    }

    override fun close() {
        isClosed = true
        locationsRegistry.close()
        runBlocking {
            cancelBackgroundJobs()
        }
        classesVfs.close()
        backgroundScope.cancel()
        persistence.close()
        hooks.forEach { it.afterStop() }
    }

    private fun canBeDumped() =
        settings.persistenceSettings.implSettings.let { persistenceSettings ->
            persistenceSettings is JIRErsSettings &&
                    persistenceSettings.ersSettings.let { ersSettings ->
                        ersSettings is RamErsSettings && ersSettings.immutableDumpsPath != null
                    }
        }

    private suspend fun List<RegisteredLocation>.process(createIndexes: Boolean): List<RegisteredLocation> {
        if (isEmpty()) {
            return this
        }
        assertIsMutable()
        withContext(Dispatchers.IO) {
            map { location ->
                async {
                    // here something may go wrong
                    location.lazySources.forEach {
                        classesVfs.addClass(it)
                    }
                }
            }
        }.awaitAll()
        val backgroundJobId = jobId.incrementAndGet()
        backgroundJobs[backgroundJobId] = backgroundScope.launch {
            val parentScope = this
            map { location ->
                async {
                    val sources = location.sources
                    parentScope.ifActive { persistence.persist(location, sources) }
                    parentScope.ifActive {
                        classesVfs.visit(
                            RemoveLocationsVisitor(
                                listOf(location),
                                settings.byteCodeSettings.prefixes
                            )
                        )
                    }
                    parentScope.ifActive { featuresRegistry.index(location, sources) }
                }
            }.joinAll()
            if (createIndexes) {
                persistence.createIndexes()
            }
            locationsRegistry.afterProcessing(this@process)
            backgroundJobs.remove(backgroundJobId)
        }
        return this
    }

    private fun assertNotClosed() {
        check(!isClosed) { "Database is already closed" }
    }

    private fun assertIsMutable() {
        check(!isImmutable) { "Database is immutable" }
    }

    private inline fun CoroutineScope.ifActive(action: () -> Unit) {
        if (isActive) {
            action()
        }
    }
}