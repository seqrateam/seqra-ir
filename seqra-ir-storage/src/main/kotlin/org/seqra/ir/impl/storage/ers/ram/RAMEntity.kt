package org.seqra.ir.impl.storage.ers.ram

import org.seqra.ir.api.storage.ers.Entity
import org.seqra.ir.api.storage.ers.EntityId
import org.seqra.ir.api.storage.ers.EntityIterable

internal class RAMEntity(override val txn: RAMTransaction, override val id: EntityId) : Entity() {

    override fun getRawProperty(name: String): ByteArray? = txn.getRawProperty(id, name)

    override fun setRawProperty(name: String, value: ByteArray?) {
        txn.setRawProperty(id, name, value)
    }

    override fun getRawBlob(name: String): ByteArray? = txn.getBlob(id, name)

    override fun setRawBlob(name: String, blob: ByteArray?) {
        txn.setBlob(id, name, blob)
    }

    override fun getLinks(name: String): EntityIterable {
        return txn.getLinks(id, name)
    }

    override fun addLink(name: String, targetId: EntityId): Boolean {
        return txn.addLink(id, name, targetId)
    }

    override fun deleteLink(name: String, targetId: EntityId): Boolean {
        return txn.deleteLink(id, name, targetId)
    }
}