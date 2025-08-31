package org.seqra.ir.impl.vfs

import org.seqra.ir.api.jvm.RegisteredLocation
import org.seqra.ir.impl.LocationsRegistrySnapshot

/**
 * ClassTree view limited by number of `locations`
 */
class ClasspathVfs(
    private val globalClassVFS: GlobalClassesVfs,
    locations: List<RegisteredLocation>
) {

    constructor(globalClassVFS: GlobalClassesVfs, locationsRegistrySnapshot: LocationsRegistrySnapshot) : this(
        globalClassVFS,
        locationsRegistrySnapshot.locations
    )

    private val locationIds: Set<Long> = locations.map { it.id }.toHashSet()

    fun firstClassOrNull(fullName: String): ClassVfsItem? {
        return globalClassVFS.firstClassNodeOrNull(fullName) {
            locationIds.contains(it)
        }
    }

    fun findClassNodes(fullName: String): List<ClassVfsItem> {
        return globalClassVFS.findClassNodes(fullName) {
            locationIds.contains(it)
        }
    }
}
