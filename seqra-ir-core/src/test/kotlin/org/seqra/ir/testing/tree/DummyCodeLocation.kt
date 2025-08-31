package org.seqra.ir.testing.tree

import org.seqra.ir.api.jvm.JIRByteCodeLocation
import org.seqra.ir.api.jvm.LocationType
import org.seqra.ir.api.jvm.RegisteredLocation
import org.seqra.ir.impl.storage.longHash
import java.io.File
import java.math.BigInteger

open class DummyCodeLocation(private val name: String) : JIRByteCodeLocation, RegisteredLocation {

    override val id: Long
        get() = name.longHash

    override val currentHash: BigInteger get() = BigInteger.valueOf(id)

    override val fileSystemIdHash: BigInteger get() = currentHash

    override val fileSystemId: String
        get() = name

    override val isRuntime: Boolean
        get() = false

    override val jIRLocation: JIRByteCodeLocation
        get() = this

    override val type = LocationType.APP

    override val classes: Map<String, ByteArray>
        get() = emptyMap()

    override val jarOrFolder: File
        get() = TODO("Not yet implemented")
    override val path: String
        get() = TODO("")

    override fun isChanged() = false

    override fun createRefreshed() = this

    override fun resolve(classFullName: String) = null

    override val classNames: Set<String>
        get() = emptySet()

}
