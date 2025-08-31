package org.seqra.ir.impl.cfg.util

import org.seqra.ir.api.jvm.TypeName
import org.seqra.ir.api.jvm.cfg.JIRRawAddExpr
import org.seqra.ir.api.jvm.cfg.JIRRawAndExpr
import org.seqra.ir.api.jvm.cfg.JIRRawArgument
import org.seqra.ir.api.jvm.cfg.JIRRawArrayAccess
import org.seqra.ir.api.jvm.cfg.JIRRawAssignInst
import org.seqra.ir.api.jvm.cfg.JIRRawBinaryExpr
import org.seqra.ir.api.jvm.cfg.JIRRawBool
import org.seqra.ir.api.jvm.cfg.JIRRawByte
import org.seqra.ir.api.jvm.cfg.JIRRawCallExpr
import org.seqra.ir.api.jvm.cfg.JIRRawCallInst
import org.seqra.ir.api.jvm.cfg.JIRRawCastExpr
import org.seqra.ir.api.jvm.cfg.JIRRawCatchInst
import org.seqra.ir.api.jvm.cfg.JIRRawChar
import org.seqra.ir.api.jvm.cfg.JIRRawClassConstant
import org.seqra.ir.api.jvm.cfg.JIRRawCmpExpr
import org.seqra.ir.api.jvm.cfg.JIRRawCmpgExpr
import org.seqra.ir.api.jvm.cfg.JIRRawCmplExpr
import org.seqra.ir.api.jvm.cfg.JIRRawConditionExpr
import org.seqra.ir.api.jvm.cfg.JIRRawDivExpr
import org.seqra.ir.api.jvm.cfg.JIRRawDouble
import org.seqra.ir.api.jvm.cfg.JIRRawDynamicCallExpr
import org.seqra.ir.api.jvm.cfg.JIRRawEnterMonitorInst
import org.seqra.ir.api.jvm.cfg.JIRRawEqExpr
import org.seqra.ir.api.jvm.cfg.JIRRawExitMonitorInst
import org.seqra.ir.api.jvm.cfg.JIRRawExpr
import org.seqra.ir.api.jvm.cfg.JIRRawExprVisitor
import org.seqra.ir.api.jvm.cfg.JIRRawFieldRef
import org.seqra.ir.api.jvm.cfg.JIRRawFloat
import org.seqra.ir.api.jvm.cfg.JIRRawGeExpr
import org.seqra.ir.api.jvm.cfg.JIRRawGotoInst
import org.seqra.ir.api.jvm.cfg.JIRRawGtExpr
import org.seqra.ir.api.jvm.cfg.JIRRawIfInst
import org.seqra.ir.api.jvm.cfg.JIRRawInst
import org.seqra.ir.api.jvm.cfg.JIRRawInstVisitor
import org.seqra.ir.api.jvm.cfg.JIRRawInstanceOfExpr
import org.seqra.ir.api.jvm.cfg.JIRRawInt
import org.seqra.ir.api.jvm.cfg.JIRRawInterfaceCallExpr
import org.seqra.ir.api.jvm.cfg.JIRRawLabelInst
import org.seqra.ir.api.jvm.cfg.JIRRawLeExpr
import org.seqra.ir.api.jvm.cfg.JIRRawLengthExpr
import org.seqra.ir.api.jvm.cfg.JIRRawLineNumberInst
import org.seqra.ir.api.jvm.cfg.JIRRawLocalVar
import org.seqra.ir.api.jvm.cfg.JIRRawLong
import org.seqra.ir.api.jvm.cfg.JIRRawLtExpr
import org.seqra.ir.api.jvm.cfg.JIRRawMethodConstant
import org.seqra.ir.api.jvm.cfg.JIRRawMethodType
import org.seqra.ir.api.jvm.cfg.JIRRawMulExpr
import org.seqra.ir.api.jvm.cfg.JIRRawNegExpr
import org.seqra.ir.api.jvm.cfg.JIRRawNeqExpr
import org.seqra.ir.api.jvm.cfg.JIRRawNewArrayExpr
import org.seqra.ir.api.jvm.cfg.JIRRawNewExpr
import org.seqra.ir.api.jvm.cfg.JIRRawNullConstant
import org.seqra.ir.api.jvm.cfg.JIRRawOrExpr
import org.seqra.ir.api.jvm.cfg.JIRRawRemExpr
import org.seqra.ir.api.jvm.cfg.JIRRawReturnInst
import org.seqra.ir.api.jvm.cfg.JIRRawShlExpr
import org.seqra.ir.api.jvm.cfg.JIRRawShort
import org.seqra.ir.api.jvm.cfg.JIRRawShrExpr
import org.seqra.ir.api.jvm.cfg.JIRRawSimpleValue
import org.seqra.ir.api.jvm.cfg.JIRRawSpecialCallExpr
import org.seqra.ir.api.jvm.cfg.JIRRawStaticCallExpr
import org.seqra.ir.api.jvm.cfg.JIRRawStringConstant
import org.seqra.ir.api.jvm.cfg.JIRRawSubExpr
import org.seqra.ir.api.jvm.cfg.JIRRawSwitchInst
import org.seqra.ir.api.jvm.cfg.JIRRawThis
import org.seqra.ir.api.jvm.cfg.JIRRawThrowInst
import org.seqra.ir.api.jvm.cfg.JIRRawUshrExpr
import org.seqra.ir.api.jvm.cfg.JIRRawValue
import org.seqra.ir.api.jvm.cfg.JIRRawVirtualCallExpr
import org.seqra.ir.api.jvm.cfg.JIRRawXorExpr

class ExprMapper(val mapping: Map<JIRRawExpr, JIRRawExpr>) : JIRRawInstVisitor<JIRRawInst>, JIRRawExprVisitor<JIRRawExpr> {

    override fun visitJIRRawAssignInst(inst: JIRRawAssignInst): JIRRawInst {
        val newLhv = inst.lhv.accept(this) as JIRRawValue
        val newRhv = inst.rhv.accept(this)
        return when {
            inst.lhv == newLhv && inst.rhv == newRhv -> inst
            else -> JIRRawAssignInst(inst.owner, newLhv, newRhv)
        }
    }

    override fun visitJIRRawEnterMonitorInst(inst: JIRRawEnterMonitorInst): JIRRawInst {
        val newMonitor = inst.monitor.accept(this) as JIRRawSimpleValue
        return when (inst.monitor) {
            newMonitor -> inst
            else -> JIRRawEnterMonitorInst(inst.owner, newMonitor)
        }
    }

    override fun visitJIRRawExitMonitorInst(inst: JIRRawExitMonitorInst): JIRRawInst {
        val newMonitor = inst.monitor.accept(this) as JIRRawSimpleValue
        return when (inst.monitor) {
            newMonitor -> inst
            else -> JIRRawExitMonitorInst(inst.owner, newMonitor)
        }
    }

    override fun visitJIRRawCallInst(inst: JIRRawCallInst): JIRRawInst {
        val newCall = inst.callExpr.accept(this) as JIRRawCallExpr
        return when (inst.callExpr) {
            newCall -> inst
            else -> JIRRawCallInst(inst.owner, newCall)
        }
    }

    override fun visitJIRRawLabelInst(inst: JIRRawLabelInst): JIRRawInst {
        return inst
    }

    override fun visitJIRRawLineNumberInst(inst: JIRRawLineNumberInst): JIRRawInst {
        return inst
    }

    override fun visitJIRRawReturnInst(inst: JIRRawReturnInst): JIRRawInst {
        val newReturn = inst.returnValue?.accept(this) as? JIRRawValue
        return when (inst.returnValue) {
            newReturn -> inst
            else -> JIRRawReturnInst(inst.owner, newReturn)
        }
    }

    override fun visitJIRRawThrowInst(inst: JIRRawThrowInst): JIRRawInst {
        val newThrowable = inst.throwable.accept(this) as JIRRawValue
        return when (inst.throwable) {
            newThrowable -> inst
            else -> JIRRawThrowInst(inst.owner, newThrowable)
        }
    }

    override fun visitJIRRawCatchInst(inst: JIRRawCatchInst): JIRRawInst {
        val newThrowable = inst.throwable.accept(this) as JIRRawValue
        return when (inst.throwable) {
            newThrowable -> inst
            else -> JIRRawCatchInst(inst.owner, newThrowable, inst.handler, inst.entries)
        }
    }

    override fun visitJIRRawGotoInst(inst: JIRRawGotoInst): JIRRawInst {
        return inst
    }

    override fun visitJIRRawIfInst(inst: JIRRawIfInst): JIRRawInst {
        val newCondition = inst.condition.accept(this) as JIRRawConditionExpr
        return when (inst.condition) {
            newCondition -> inst
            else -> JIRRawIfInst(inst.owner, newCondition, inst.trueBranch, inst.falseBranch)
        }
    }

    override fun visitJIRRawSwitchInst(inst: JIRRawSwitchInst): JIRRawInst {
        val newKey = inst.key.accept(this) as JIRRawValue
        val newBranches = inst.branches.mapKeys { it.key.accept(this) as JIRRawValue }
        return when {
            inst.key == newKey && inst.branches == newBranches -> inst
            else -> JIRRawSwitchInst(inst.owner, newKey, newBranches, inst.default)
        }
    }

    private inline fun <T : JIRRawExpr> exprHandler(expr: T, handler: () -> JIRRawExpr): JIRRawExpr {
        return mapping.getOrElse(expr, handler)
    }

    private inline fun <T : JIRRawBinaryExpr> binaryHandler(expr: T, handler: (TypeName, JIRRawValue, JIRRawValue) -> T) =
        exprHandler(expr) {
            val newLhv = expr.lhv.accept(this) as JIRRawValue
            val newRhv = expr.rhv.accept(this) as JIRRawValue
            when {
                expr.lhv == newLhv && expr.rhv == newRhv -> expr
                else -> handler(newLhv.typeName, newLhv, newRhv)
            }
        }

    override fun visitJIRRawAddExpr(expr: JIRRawAddExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawAddExpr(type, lhv, rhv)
    }

    override fun visitJIRRawAndExpr(expr: JIRRawAndExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawAndExpr(type, lhv, rhv)
    }

    override fun visitJIRRawCmpExpr(expr: JIRRawCmpExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawCmpExpr(type, lhv, rhv)
    }

    override fun visitJIRRawCmpgExpr(expr: JIRRawCmpgExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawCmpgExpr(type, lhv, rhv)
    }

    override fun visitJIRRawCmplExpr(expr: JIRRawCmplExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawCmplExpr(type, lhv, rhv)
    }

    override fun visitJIRRawDivExpr(expr: JIRRawDivExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawDivExpr(type, lhv, rhv)
    }

    override fun visitJIRRawMulExpr(expr: JIRRawMulExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawMulExpr(type, lhv, rhv)
    }

    override fun visitJIRRawEqExpr(expr: JIRRawEqExpr) = binaryHandler(expr) { _, lhv, rhv ->
        JIRRawEqExpr(expr.typeName, lhv, rhv)
    }

    override fun visitJIRRawNeqExpr(expr: JIRRawNeqExpr) = binaryHandler(expr) { _, lhv, rhv ->
        JIRRawNeqExpr(expr.typeName, lhv, rhv)
    }

    override fun visitJIRRawGeExpr(expr: JIRRawGeExpr) = binaryHandler(expr) { _, lhv, rhv ->
        JIRRawGeExpr(expr.typeName, lhv, rhv)
    }

    override fun visitJIRRawGtExpr(expr: JIRRawGtExpr) = binaryHandler(expr) { _, lhv, rhv ->
        JIRRawGtExpr(expr.typeName, lhv, rhv)
    }

    override fun visitJIRRawLeExpr(expr: JIRRawLeExpr) = binaryHandler(expr) { _, lhv, rhv ->
        JIRRawLeExpr(expr.typeName, lhv, rhv)
    }

    override fun visitJIRRawLtExpr(expr: JIRRawLtExpr) = binaryHandler(expr) { _, lhv, rhv ->
        JIRRawLtExpr(expr.typeName, lhv, rhv)
    }

    override fun visitJIRRawOrExpr(expr: JIRRawOrExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawOrExpr(type, lhv, rhv)
    }

    override fun visitJIRRawRemExpr(expr: JIRRawRemExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawRemExpr(type, lhv, rhv)
    }

    override fun visitJIRRawShlExpr(expr: JIRRawShlExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawShlExpr(type, lhv, rhv)
    }

    override fun visitJIRRawShrExpr(expr: JIRRawShrExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawShrExpr(type, lhv, rhv)
    }

    override fun visitJIRRawSubExpr(expr: JIRRawSubExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawSubExpr(type, lhv, rhv)
    }

    override fun visitJIRRawUshrExpr(expr: JIRRawUshrExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawUshrExpr(type, lhv, rhv)
    }

    override fun visitJIRRawXorExpr(expr: JIRRawXorExpr) = binaryHandler(expr) { type, lhv, rhv ->
        JIRRawXorExpr(type, lhv, rhv)
    }

    override fun visitJIRRawLengthExpr(expr: JIRRawLengthExpr) = exprHandler(expr) {
        val newArray = expr.array.accept(this) as JIRRawValue
        when (expr.array) {
            newArray -> expr
            else -> JIRRawLengthExpr(expr.typeName, newArray)
        }
    }

    override fun visitJIRRawNegExpr(expr: JIRRawNegExpr) = exprHandler(expr) {
        val newOperand = expr.operand.accept(this) as JIRRawValue
        when (expr.operand) {
            newOperand -> expr
            else -> JIRRawNegExpr(newOperand.typeName, newOperand)
        }
    }

    override fun visitJIRRawCastExpr(expr: JIRRawCastExpr) = exprHandler(expr) {
        val newOperand = expr.operand.accept(this) as JIRRawValue
        when (expr.operand) {
            newOperand -> expr
            else -> JIRRawCastExpr(expr.typeName, newOperand)
        }
    }

    override fun visitJIRRawNewExpr(expr: JIRRawNewExpr) = exprHandler(expr) { expr }

    override fun visitJIRRawNewArrayExpr(expr: JIRRawNewArrayExpr) = exprHandler(expr) {
        val newDimensions = expr.dimensions.map { it.accept(this) as JIRRawValue }
        when (expr.dimensions) {
            newDimensions -> expr
            else -> JIRRawNewArrayExpr(expr.typeName, newDimensions)
        }
    }

    override fun visitJIRRawInstanceOfExpr(expr: JIRRawInstanceOfExpr) = exprHandler(expr) {
        val newOperand = expr.operand.accept(this) as JIRRawValue
        when (expr.operand) {
            newOperand -> expr
            else -> JIRRawInstanceOfExpr(expr.typeName, newOperand, expr.targetType)
        }
    }

    override fun visitJIRRawDynamicCallExpr(expr: JIRRawDynamicCallExpr) = exprHandler(expr) {
        val newArgs = expr.args.map { it.accept(this) as JIRRawValue }
        when (expr.args) {
            newArgs -> expr
            else -> JIRRawDynamicCallExpr(
                expr.bsm,
                expr.bsmArgs,
                expr.callSiteMethodName,
                expr.callSiteArgTypes,
                expr.callSiteReturnType,
                newArgs
            )
        }
    }

    override fun visitJIRRawVirtualCallExpr(expr: JIRRawVirtualCallExpr) = exprHandler(expr) {
        val newInstance = expr.instance.accept(this) as JIRRawValue
        val newArgs = expr.args.map { it.accept(this) as JIRRawValue }
        when {
            expr.instance == newInstance && expr.args == newArgs -> expr
            else -> JIRRawVirtualCallExpr(
                expr.declaringClass,
                expr.methodName,
                expr.argumentTypes,
                expr.returnType,
                newInstance,
                newArgs
            )
        }
    }

    override fun visitJIRRawInterfaceCallExpr(expr: JIRRawInterfaceCallExpr) = exprHandler(expr) {
        val newInstance = expr.instance.accept(this) as JIRRawValue
        val newArgs = expr.args.map { it.accept(this) as JIRRawValue }
        when {
            expr.instance == newInstance && expr.args == newArgs -> expr
            else -> JIRRawInterfaceCallExpr(
                expr.declaringClass,
                expr.methodName,
                expr.argumentTypes,
                expr.returnType,
                newInstance,
                newArgs
            )
        }
    }

    override fun visitJIRRawStaticCallExpr(expr: JIRRawStaticCallExpr) = exprHandler(expr) {
        val newArgs = expr.args.map { it.accept(this) as JIRRawValue }
        when (expr.args) {
            newArgs -> expr
            else -> JIRRawStaticCallExpr(
                expr.declaringClass, expr.methodName, expr.argumentTypes, expr.returnType, newArgs, expr.isInterfaceMethodCall
            )
        }
    }

    override fun visitJIRRawSpecialCallExpr(expr: JIRRawSpecialCallExpr) = exprHandler(expr) {
        val newInstance = expr.instance.accept(this) as JIRRawValue
        val newArgs = expr.args.map { it.accept(this) as JIRRawValue }
        when {
            expr.instance == newInstance && expr.args == newArgs -> expr
            else -> JIRRawSpecialCallExpr(
                expr.declaringClass,
                expr.methodName,
                expr.argumentTypes,
                expr.returnType,
                newInstance,
                newArgs
            )
        }
    }


    override fun visitJIRRawThis(value: JIRRawThis) = exprHandler(value) { value }
    override fun visitJIRRawArgument(value: JIRRawArgument) = exprHandler(value) { value }
    override fun visitJIRRawLocalVar(value: JIRRawLocalVar) = exprHandler(value) { value }

    override fun visitJIRRawFieldRef(value: JIRRawFieldRef) = exprHandler(value) {
        val newInstance = value.instance?.accept(this) as? JIRRawValue
        when (value.instance) {
            newInstance -> value
            else -> JIRRawFieldRef(newInstance, value.declaringClass, value.fieldName, value.typeName)
        }
    }

    override fun visitJIRRawArrayAccess(value: JIRRawArrayAccess) = exprHandler(value) {
        val newArray = value.array.accept(this) as JIRRawValue
        val newIndex = value.index.accept(this) as JIRRawValue
        when {
            value.array == newArray && value.index == newIndex -> value
            else -> JIRRawArrayAccess(newArray, newIndex, value.typeName)
        }
    }

    override fun visitJIRRawBool(value: JIRRawBool) = exprHandler(value) { value }
    override fun visitJIRRawByte(value: JIRRawByte) = exprHandler(value) { value }
    override fun visitJIRRawChar(value: JIRRawChar) = exprHandler(value) { value }
    override fun visitJIRRawShort(value: JIRRawShort) = exprHandler(value) { value }
    override fun visitJIRRawInt(value: JIRRawInt) = exprHandler(value) { value }
    override fun visitJIRRawLong(value: JIRRawLong) = exprHandler(value) { value }
    override fun visitJIRRawFloat(value: JIRRawFloat) = exprHandler(value) { value }
    override fun visitJIRRawDouble(value: JIRRawDouble) = exprHandler(value) { value }
    override fun visitJIRRawNullConstant(value: JIRRawNullConstant) = exprHandler(value) { value }
    override fun visitJIRRawStringConstant(value: JIRRawStringConstant) = exprHandler(value) { value }
    override fun visitJIRRawClassConstant(value: JIRRawClassConstant) = exprHandler(value) { value }
    override fun visitJIRRawMethodConstant(value: JIRRawMethodConstant) = exprHandler(value) { value }
    override fun visitJIRRawMethodType(value: JIRRawMethodType) = exprHandler(value) { value }
}
