package org.seqra.ir.impl

import org.seqra.ir.api.jvm.JIRByteCodeLocation
import org.seqra.ir.api.jvm.RegisteredLocation
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

interface LocationsRegistry : Closeable {
    // all locations
    val actualLocations: List<RegisteredLocation>
    val runtimeLocations: List<RegisteredLocation>

    // all snapshot associated with classpaths
    val snapshots: ConcurrentHashMap.KeySetView<LocationsRegistrySnapshot, Boolean>

    fun cleanup(): CleanupResult
    fun refresh(): RefreshResult
    fun setup(runtimeLocations: List<JIRByteCodeLocation>): RegistrationResult

    fun registerIfNeeded(locations: List<JIRByteCodeLocation>): RegistrationResult
    fun afterProcessing(locations: List<RegisteredLocation>)

    fun newSnapshot(classpathSetLocations: List<RegisteredLocation>): LocationsRegistrySnapshot

    fun close(snapshot: LocationsRegistrySnapshot)

    fun RegisteredLocation.hasReferences(snapshots: Set<LocationsRegistrySnapshot>): Boolean {
        return snapshots.isNotEmpty() && snapshots.any { it.ids.contains(id) }
    }

}

class RegistrationResult(val registered: List<RegisteredLocation>, val new: List<RegisteredLocation>)
class RefreshResult(val new: List<RegisteredLocation>)
class CleanupResult(val outdated: List<RegisteredLocation>)

open class LocationsRegistrySnapshot(
    private val registry: LocationsRegistry,
    val locations: List<RegisteredLocation>
) : Closeable {

    val ids = locations.mapTo(mutableSetOf()) { it.id }

    override fun close() = registry.close(this)

}
