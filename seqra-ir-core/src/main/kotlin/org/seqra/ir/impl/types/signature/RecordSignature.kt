package org.seqra.ir.impl.types.signature

import org.seqra.ir.api.jvm.JvmType
import org.seqra.ir.api.jvm.Malformed
import org.seqra.ir.api.jvm.Pure
import org.seqra.ir.api.jvm.RecordComponentResolution
import org.objectweb.asm.signature.SignatureReader

internal class RecordSignature : TypeRegistrant {

    private lateinit var recordComponentType: JvmType

    override fun register(token: JvmType) {
        recordComponentType = token
    }

    protected fun resolve(): RecordComponentResolution {
        return RecordComponentResolutionImpl(recordComponentType)
    }

    companion object {
        fun of(signature: String?): RecordComponentResolution {
            signature ?: return Pure
            val signatureReader = SignatureReader(signature)
            val visitor = RecordSignature()
            return try {
                signatureReader.acceptType(TypeExtractor(visitor))
                visitor.resolve()
            } catch (ignored: RuntimeException) {
                Malformed
            }
        }
    }
}
