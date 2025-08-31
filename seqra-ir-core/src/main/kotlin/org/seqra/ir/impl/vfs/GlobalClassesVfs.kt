package org.seqra.ir.impl.vfs

import org.seqra.ir.api.jvm.ClassSource
import org.seqra.ir.api.jvm.RegisteredLocation
import java.io.Closeable

open class GlobalClassesVfs : Closeable {

    private val rootItem = PackageVfsItem(null, null)

    fun addClass(source: ClassSource): ClassVfsItem {
        val splitted = source.className.splitted
        val simpleClassName = splitted[splitted.size - 1]
        if (splitted.size == 1) {
            return rootItem.findClassOrNew(simpleClassName, source)
        }
        var node = rootItem
        var index = 0
        while (index < splitted.size - 1) {
            val subfolderName = splitted[index]
            node = node.findPackageOrNew(subfolderName)
            index++
        }
        return node.findClassOrNew(simpleClassName, source)
    }

    fun findClassNodeOrNull(codeLocation: RegisteredLocation, fullName: String): ClassVfsItem? {
        val splitted = fullName.splitted
        val simpleClassName = splitted[splitted.size - 1]
        return findPackage(splitted)?.firstClassOrNull(simpleClassName, codeLocation.id)
    }

    fun firstClassNodeOrNull(fullName: String, predicate: (Long) -> Boolean = { true }): ClassVfsItem? {
        val splitted = fullName.splitted
        val simpleClassName = splitted[splitted.size - 1]
        return findPackage(splitted)?.firstClassOrNull(simpleClassName, predicate)
    }

    fun findClassNodes(fullName: String, predicate: (Long) -> Boolean = { true }): List<ClassVfsItem> {
        val splitted = fullName.splitted
        val simpleClassName = splitted[splitted.size - 1]
        return findPackage(splitted)?.findClasses(simpleClassName, predicate) ?: emptyList()
    }

    private fun findPackage(splitted: List<String>): PackageVfsItem? {
        var node: PackageVfsItem? = rootItem
        var index = 0
        while (index < (splitted.size - 1)) {
            if (node == null) {
                return null
            }
            val subfolderName = splitted[index]
            node = node.findPackageOrNull(subfolderName)
            index++
        }
        return node
    }

    fun visit(visitor: VfsVisitor) {
        rootItem.visit(visitor)
    }

    private val String.splitted get() = this.split(".")

    override fun close() {
        rootItem.clear()
    }
}
