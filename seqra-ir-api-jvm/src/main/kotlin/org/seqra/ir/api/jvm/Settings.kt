package org.seqra.ir.api.jvm

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.seqra.ir.api.caches.ValueStoreType
import java.io.File
import java.time.Duration

/**
 * Settings for database
 */
class JIRSettings {

    /** watch file system changes delay */
    var watchFileSystemDelay: Int? = null
        private set

    /** persisted  */
    val persistenceId: String?
        get() = persistenceSettings.persistenceId

    val persistenceLocation: String?
        get() = persistenceSettings.persistenceLocation

    val persistenceClearOnStart: Boolean?
        get() = persistenceSettings.persistenceClearOnStart

    var persistenceSettings: JIRPersistenceSettings = JIRPersistenceSettings()

    var keepLocalVariableNames: Boolean = false
        private set

    var buildModelForJRE: Boolean = true
        private set

    /** jar files which should be loaded right after database is created */
    var predefinedDirOrJars: List<File> = persistentListOf()
        private set

    var cacheSettings: JIRCacheSettings = JIRCacheSettings()
        private set

    var byteCodeSettings: JIRByteCodeCache = JIRByteCodeCache()
        private set

    var hooks: MutableList<(JIRDatabase) -> Hook> = arrayListOf()
        private set

    /** mandatory setting for java runtime location */
    lateinit var jre: File

    /** features to add */
    var features: List<JIRFeature<*, *>> = emptyList()
        private set

    init {
        useProcessJavaRuntime()
    }

    /**
     * builder for persistent settings
     * @param location - file for db location
     * @param clearOnStart -if true old data from this folder will be dropped
     */
    @JvmOverloads
    fun persistent(
        location: String,
        clearOnStart: Boolean = false,
        implSettings: JIRPersistenceImplSettings? = null
    ) = apply {
        persistenceSettings.persistenceLocation = location
        persistenceSettings.persistenceClearOnStart = clearOnStart
        persistenceSettings.implSettings = implSettings
    }

    fun persistenceImpl(persistenceImplSettings: JIRPersistenceImplSettings) = apply {
        persistenceSettings.implSettings = persistenceImplSettings
    }

    fun caching(settings: JIRCacheSettings.() -> Unit) = apply {
        cacheSettings = JIRCacheSettings().also { it.settings() }
    }

    fun caching(settings: JIRCacheSettings) = apply {
        cacheSettings = settings
    }

    fun bytecodeCaching(byteCodeCache: JIRByteCodeCache) = apply {
        this.byteCodeSettings = byteCodeCache
    }

    fun loadByteCode(files: List<File>) = apply {
        predefinedDirOrJars = (predefinedDirOrJars + files).toPersistentList()
    }

    fun keepLocalVariableNames() = apply {
        keepLocalVariableNames = true
    }

    fun buildModelForJRE(build: Boolean) = apply {
        buildModelForJRE = build
    }

    /**
     * builder for watching file system changes
     * @param delay - delay between syncs
     */
    @JvmOverloads
    fun watchFileSystem(delay: Int = 10_000) = apply {
        watchFileSystemDelay = delay
    }

    /** builder for hooks */
    fun withHook(hook: (JIRDatabase) -> Hook) = apply {
        hooks += hook
    }

    /**
     * use java from JAVA_HOME env variable
     */
    fun useJavaHomeRuntime() = apply {
        val javaHome = System.getenv("JAVA_HOME") ?: throw IllegalArgumentException("JAVA_HOME is not set")
        jre = javaHome.asValidJRE()
    }

    /**
     * use java from current system process
     */
    fun useProcessJavaRuntime() = apply {
        val javaHome = System.getProperty("java.home") ?: throw IllegalArgumentException("java.home is not set")
        jre = javaHome.asValidJRE()
    }

    /**
     * use java from current system process
     */
    fun useJavaRuntime(runtime: File) = apply {
        jre = runtime.absolutePath.asValidJRE()
    }

    /**
     * install additional indexes
     */
    fun installFeatures(vararg feature: JIRFeature<*, *>) = apply {
        features = features + feature.toList()
    }

    private fun String.asValidJRE(): File {
        val file = File(this)
        if (!file.exists()) {
            throw IllegalArgumentException("$this points to folder that do not exists")
        }
        return file
    }
}

class JIRPersistenceSettings {
    val persistenceId: String? get() = implSettings?.persistenceId
    var persistenceLocation: String? = null
    var persistenceClearOnStart: Boolean? = null
    var implSettings: JIRPersistenceImplSettings? = null
}

interface JIRPersistenceImplSettings {
    val persistenceId: String
}

data class JIRCacheSegmentSettings(
    val valueStoreType: ValueStoreType = ValueStoreType.STRONG,
    val maxSize: Long = 10_000,
    val expiration: Duration = Duration.ofMinutes(1)
)

class JIRCacheSettings {
    var cacheSpiId: String? = null
    var classes: JIRCacheSegmentSettings = JIRCacheSegmentSettings()
    var types: JIRCacheSegmentSettings = JIRCacheSegmentSettings()
    var rawInstLists: JIRCacheSegmentSettings = JIRCacheSegmentSettings()
    var instLists: JIRCacheSegmentSettings = JIRCacheSegmentSettings()
    var flowGraphs: JIRCacheSegmentSettings = JIRCacheSegmentSettings()

    @JvmOverloads
    fun classes(maxSize: Long, expiration: Duration, valueStoreType: ValueStoreType = ValueStoreType.STRONG) = apply {
        classes = JIRCacheSegmentSettings(maxSize = maxSize, expiration = expiration, valueStoreType = valueStoreType)
    }

    @JvmOverloads
    fun types(maxSize: Long, expiration: Duration, valueStoreType: ValueStoreType = ValueStoreType.STRONG) = apply {
        types = JIRCacheSegmentSettings(maxSize = maxSize, expiration = expiration, valueStoreType = valueStoreType)
    }

    @JvmOverloads
    fun rawInstLists(maxSize: Long, expiration: Duration, valueStoreType: ValueStoreType = ValueStoreType.STRONG) =
        apply {
            rawInstLists =
                JIRCacheSegmentSettings(maxSize = maxSize, expiration = expiration, valueStoreType = valueStoreType)
        }

    @JvmOverloads
    fun instLists(maxSize: Long, expiration: Duration, valueStoreType: ValueStoreType = ValueStoreType.STRONG) = apply {
        instLists = JIRCacheSegmentSettings(maxSize = maxSize, expiration = expiration, valueStoreType = valueStoreType)
    }

    @JvmOverloads
    fun flowGraphs(maxSize: Long, expiration: Duration, valueStoreType: ValueStoreType = ValueStoreType.STRONG) =
        apply {
            flowGraphs =
                JIRCacheSegmentSettings(maxSize = maxSize, expiration = expiration, valueStoreType = valueStoreType)
        }
}

class JIRByteCodeCache(val prefixes: List<String> = persistentListOf("java.", "javax.", "kotlinx.", "kotlin."))