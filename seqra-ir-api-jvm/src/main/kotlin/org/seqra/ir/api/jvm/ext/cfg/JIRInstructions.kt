
@file:JvmName("JIRInstructions")
package org.seqra.ir.api.jvm.ext.cfg

import org.seqra.ir.api.common.cfg.CommonExpr
import org.seqra.ir.api.common.cfg.CommonInst
import org.seqra.ir.api.jvm.cfg.JIRArrayAccess
import org.seqra.ir.api.jvm.cfg.JIRCallExpr
import org.seqra.ir.api.jvm.cfg.JIRExpr
import org.seqra.ir.api.jvm.cfg.JIRExprVisitor
import org.seqra.ir.api.jvm.cfg.JIRFieldRef
import org.seqra.ir.api.jvm.cfg.JIRInst
import org.seqra.ir.api.jvm.cfg.JIRInstList
import org.seqra.ir.api.jvm.cfg.JIRInstVisitor
import org.seqra.ir.api.jvm.cfg.JIRLocal
import org.seqra.ir.api.jvm.cfg.JIRRawExpr
import org.seqra.ir.api.jvm.cfg.JIRRawExprVisitor
import org.seqra.ir.api.jvm.cfg.JIRRawInst
import org.seqra.ir.api.jvm.cfg.JIRRawInstVisitor
import org.seqra.ir.api.jvm.cfg.JIRValue
import org.seqra.ir.api.jvm.cfg.LocalResolver
import org.seqra.ir.api.jvm.cfg.ValueResolver

fun JIRInstList<JIRRawInst>.apply(visitor: JIRRawInstVisitor<Unit>): JIRInstList<JIRRawInst> {
    instructions.forEach { it.accept(visitor) }
    return this
}

fun <R, E, T : JIRRawInstVisitor<E>> JIRInstList<JIRRawInst>.applyAndGet(visitor: T, getter: (T) -> R): R {
    instructions.forEach { it.accept(visitor) }
    return getter(visitor)
}

fun <T> JIRInstList<JIRRawInst>.collect(visitor: JIRRawInstVisitor<T>): Collection<T> {
    return instructions.map { it.accept(visitor) }
}

fun <R, E, T : JIRRawInstVisitor<E>> JIRRawInst.applyAndGet(visitor: T, getter: (T) -> R): R {
    this.accept(visitor)
    return getter(visitor)
}

fun <R, E, T : JIRRawExprVisitor<E>> JIRRawExpr.applyAndGet(visitor: T, getter: (T) -> R): R {
    this.accept(visitor)
    return getter(visitor)
}

object FieldRefVisitor :
    JIRExprVisitor.Default<JIRFieldRef?>,
    JIRInstVisitor.Default<JIRFieldRef?> {

    override fun defaultVisitJIRExpr(expr: JIRExpr): JIRFieldRef? {
        return expr.operands.filterIsInstance<JIRFieldRef>().firstOrNull()
    }

    override fun defaultVisitJIRInst(inst: JIRInst): JIRFieldRef? {
        return inst.operands.map { it.accept(this) }.firstOrNull { it != null }
    }

    override fun visitJIRFieldRef(value: JIRFieldRef): JIRFieldRef {
        return value
    }
}

object ArrayAccessVisitor :
    JIRExprVisitor.Default<JIRArrayAccess?>,
    JIRInstVisitor.Default<JIRArrayAccess?> {

    override fun defaultVisitJIRExpr(expr: JIRExpr): JIRArrayAccess? {
        return expr.operands.filterIsInstance<JIRArrayAccess>().firstOrNull()
    }

    override fun defaultVisitJIRInst(inst: JIRInst): JIRArrayAccess? {
        return inst.operands.map { it.accept(this) }.firstOrNull { it != null }
    }

    override fun visitJIRArrayAccess(value: JIRArrayAccess): JIRArrayAccess {
        return value
    }
}

object CallExprVisitor : JIRInstVisitor.Default<JIRCallExpr?> {
    override fun defaultVisitJIRInst(inst: JIRInst): JIRCallExpr? {
        return inst.operands.filterIsInstance<JIRCallExpr>().firstOrNull()
    }
}

val JIRInst.fieldRef: JIRFieldRef?
    get() {
        return accept(FieldRefVisitor)
    }

val JIRInst.arrayRef: JIRArrayAccess?
    get() {
        return accept(ArrayAccessVisitor)
    }

val JIRInst.callExpr: JIRCallExpr?
    get() {
        return accept(CallExprVisitor)
    }

val JIRInstList<JIRInst>.locals: Set<JIRLocal>
    get() {
        val resolver = LocalResolver().also { res ->
            forEach { it.accept(res) }
        }
        return resolver.result
    }

val JIRInstList<JIRInst>.values: Set<JIRValue>
    get() {
        val resolver = ValueResolver().also { res ->
            forEach { it.accept(res) }
        }
        return resolver.result
    }
