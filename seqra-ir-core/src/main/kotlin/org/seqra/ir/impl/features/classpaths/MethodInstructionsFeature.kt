package org.seqra.ir.impl.features.classpaths

import org.seqra.ir.api.jvm.JIRFeatureEvent
import org.seqra.ir.api.jvm.JIRInstExtFeature
import org.seqra.ir.api.jvm.JIRMethod
import org.seqra.ir.api.jvm.JIRMethodExtFeature
import org.seqra.ir.api.jvm.JIRMethodExtFeature.JIRInstListResult
import org.seqra.ir.api.jvm.cfg.JIRInst
import org.seqra.ir.api.jvm.cfg.JIRInstList
import org.seqra.ir.api.jvm.cfg.JIRRawInst
import org.seqra.ir.impl.cfg.JIRGraphImpl
import org.seqra.ir.impl.cfg.JIRInstListBuilder
import org.seqra.ir.impl.cfg.RawInstListBuilder
import org.seqra.ir.impl.features.JIRFeatureEventImpl
import org.seqra.ir.impl.features.classpaths.AbstractJIRInstResult.JIRFlowGraphResultImpl
import org.seqra.ir.impl.features.classpaths.AbstractJIRInstResult.JIRInstListResultImpl
import org.seqra.ir.impl.features.classpaths.AbstractJIRInstResult.JIRRawInstListResultImpl

class MethodInstructionsFeature(
    private val keepLocalVariableNames: Boolean
) : JIRMethodExtFeature {

    private val JIRMethod.methodFeatures
        get() = enclosingClass.classpath.features?.filterIsInstance<JIRInstExtFeature>().orEmpty()

    override fun flowGraph(method: JIRMethod): JIRMethodExtFeature.JIRFlowGraphResult {
        return JIRFlowGraphResultImpl(method, JIRGraphImpl(method, method.instList.instructions))
    }

    override fun instList(method: JIRMethod): JIRInstListResult {
        val list: JIRInstList<JIRInst> = JIRInstListBuilder(method, method.rawInstList).buildInstList()
        return JIRInstListResultImpl(method, method.methodFeatures.fold(list) { value, feature ->
            feature.transformInstList(method, value)
        })
    }

    override fun rawInstList(method: JIRMethod): JIRMethodExtFeature.JIRRawInstListResult {
        val list: JIRInstList<JIRRawInst> = method.withAsmNode { methodNode ->
            RawInstListBuilder(method, methodNode, keepLocalVariableNames).build()
        }
        return JIRRawInstListResultImpl(method, method.methodFeatures.fold(list) { value, feature ->
            feature.transformRawInstList(method, value)
        })
    }

    override fun event(result: Any): JIRFeatureEvent {
        return JIRFeatureEventImpl(this, result)
    }

}
