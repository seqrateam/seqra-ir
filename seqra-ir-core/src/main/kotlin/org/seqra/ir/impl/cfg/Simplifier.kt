package org.seqra.ir.impl.cfg

import org.seqra.ir.api.jvm.cfg.AbstractFullRawExprSetCollector
import org.seqra.ir.api.jvm.cfg.JIRInstList
import org.seqra.ir.api.jvm.cfg.JIRRawAssignInst
import org.seqra.ir.api.jvm.cfg.JIRRawCatchInst
import org.seqra.ir.api.jvm.cfg.JIRRawComplexValue
import org.seqra.ir.api.jvm.cfg.JIRRawConstant
import org.seqra.ir.api.jvm.cfg.JIRRawExpr
import org.seqra.ir.api.jvm.cfg.JIRRawInst
import org.seqra.ir.api.jvm.cfg.JIRRawLabelInst
import org.seqra.ir.api.jvm.cfg.JIRRawLocalVar
import org.seqra.ir.api.jvm.cfg.JIRRawNullConstant
import org.seqra.ir.api.jvm.cfg.JIRRawSimpleValue
import org.seqra.ir.api.jvm.cfg.JIRRawValue
import org.seqra.ir.api.jvm.ext.cfg.applyAndGet
import org.seqra.ir.impl.cfg.util.ExprMapper
import org.seqra.ir.impl.cfg.util.InstructionFilter

/**
 * a class that simplifies the instruction list after construction
 * a simplification process is required, because the construction process
 * naturally introduces some redundancy into the code (mainly because of
 * the frames merging)
 */
internal class Simplifier {
    fun simplify(instList: JIRInstList<JIRRawInst>): JIRInstList<JIRRawInst> {
        // clear the assignments that are repeated inside single basic block
        var instructionList = cleanRepeatedAssignments(instList)

        do {
            // delete the assignments that are not used anywhere in the code
            // need to run this repeatedly, because deleting one instruction may
            // free another one
            val uses = computeUseCases(instructionList)
            val oldSize = instructionList.instructions.size
            instructionList = instructionList.filterNot(InstructionFilter {
                it is JIRRawAssignInst
                    && it.lhv is JIRRawSimpleValue
                    && it.rhv is JIRRawValue
                    && uses.getOrDefault(it.lhv, 0) == 0
            })
        } while (instructionList.instructions.size != oldSize)

        do {
            // delete the assignments that are mutually dependent only on one another
            // (e.g. `a = b` and `b = a`) and not used anywhere else; also need to run several times
            // because of potential dependencies between such variables
            val assignmentsMap = computeAssignments(instructionList)
            val replacements = buildMap {
                for ((to, froms) in assignmentsMap) {
                    if (froms.size > 1) {
                        continue
                    }
                    val firstFrom = (froms.first() as? JIRRawLocalVar) ?: continue
                    val fromAssignments = assignmentsMap[firstFrom]
                    if (fromAssignments != null && fromAssignments.size != 1) {
                        continue
                    }
                    put(to, firstFrom)
                }
            }

            val extendedReplacements = buildMap<JIRRawExpr, _> {
                for ((to, from) in replacements) {
                    if (!to.name.startsWith(LOCAL_VAR_START_CHARACTER)) {
                        val actual = to.copy(typeName = from.typeName)
                        // to keep original names ---------^
                        put(to, actual)
                        put(from, actual)
                    } else {
                        put(to, from)
                    }
                }
            }

            instructionList = instructionList
                .filterNot(InstructionFilter {
                    if (it !is JIRRawAssignInst) return@InstructionFilter false
                    val lhv = it.lhv as? JIRRawSimpleValue ?: return@InstructionFilter false
                    val rhv = it.rhv as? JIRRawSimpleValue ?: return@InstructionFilter false
                    replacements[lhv] == rhv && replacements[rhv] == lhv
                })
                .map(ExprMapper(extendedReplacements))
                .filterNot(InstructionFilter {
                    it is JIRRawAssignInst && it.rhv == it.lhv
                })
        } while (replacements.isNotEmpty())

        do {
            // trying to remove all the simple variables that are equivalent to some other simple variable
            val uses = computeUseCases(instructionList)
            val (replacements, instructionsToDelete) = computeReplacements(instructionList, uses)
            instructionList = instructionList
                .map(ExprMapper(replacements))
                .filter(InstructionFilter { it !in instructionsToDelete })
        } while (replacements.isNotEmpty())

        // remove instructions like `a = a`
        instructionList = cleanSelfAssignments(instructionList)
        // fix some typing errors and normalize the types of all local variables
        return normalizeTypes(instructionList)
    }

    private fun computeUseCases(instList: JIRInstList<JIRRawInst>): Map<JIRRawSimpleValue, Set<JIRRawInst>> {
        val uses = hashMapOf<JIRRawSimpleValue, MutableSet<JIRRawInst>>()
        for (inst in instList) {
            when (inst) {
                is JIRRawAssignInst -> {
                    if (inst.lhv is JIRRawComplexValue) {
                        inst.lhv.applyAndGet(SimplifierCollector()) { it.exprs }
                            .forEach {
                                uses.getOrPut(it, ::mutableSetOf).add(inst)
                            }
                    }
                    inst.rhv.applyAndGet(SimplifierCollector()) { it.exprs }
                        .forEach {
                            uses.getOrPut(it, ::mutableSetOf).add(inst)
                        }
                }

                is JIRRawCatchInst -> {}

                else -> {
                    inst.applyAndGet(SimplifierCollector()) { it.exprs }
                        .forEach {
                            uses.getOrPut(it, ::mutableSetOf).add(inst)
                        }
                }
            }
        }
        return uses
    }

    private fun cleanRepeatedAssignments(instList: JIRInstList<JIRRawInst>): JIRInstList<JIRRawInst> {
        val instructions = mutableListOf<JIRRawInst>()
        val equalities = hashMapOf<JIRRawSimpleValue, JIRRawSimpleValue>()
        for (inst in instList) {
            when (inst) {
                is JIRRawAssignInst -> {
                    val lhv = inst.lhv
                    val rhv = inst.rhv
                    if (lhv is JIRRawSimpleValue && rhv is JIRRawSimpleValue) {
                        val iterator = equalities.entries.iterator()
                        while (iterator.hasNext()) {
                            val entry = iterator.next()
                            if (entry.value == lhv) {
                                iterator.remove()
                            }
                        }
                        if (equalities[lhv] != rhv) {
                            equalities[lhv] = rhv
                            instructions += inst
                        }
                    } else {
                        instructions += inst
                    }
                }

                is JIRRawLabelInst -> {
                    instructions += inst
                    equalities.clear()
                }

                else -> instructions += inst
            }
        }
        return JIRInstListImpl(instructions)
    }

    private fun cleanSelfAssignments(instList: JIRInstList<JIRRawInst>): JIRInstList<JIRRawInst> {
        val instructions = mutableListOf<JIRRawInst>()
        for (inst in instList) {
            when (inst) {
                is JIRRawAssignInst -> {
                    if (inst.lhv != inst.rhv) {
                        instructions += inst
                    }
                }

                else -> instructions += inst
            }
        }
        return JIRInstListImpl(instructions)
    }

    private fun computeReplacements(
        instList: JIRInstList<JIRRawInst>,
        uses: Map<JIRRawSimpleValue, Set<JIRRawInst>>,
    ): Pair<Map<JIRRawExpr, JIRRawExpr>, Set<JIRRawInst>> {
        val replacements = hashMapOf<JIRRawExpr, JIRRawExpr>()
        val reservedValues = hashSetOf<JIRRawValue>()
        val replacedInsts = hashSetOf<JIRRawInst>()

        val instructionIndex = identityMap<JIRRawInst, Int>()
        val assignInstructions = mutableListOf<JIRRawAssignInst>()
        val firstValueAssignment = hashMapOf<JIRRawValue, JIRRawAssignInst>()

        for ((i, inst) in instList.withIndex()) {
            instructionIndex[inst] = i

            if (inst !is JIRRawAssignInst) continue

            assignInstructions.add(inst)
            firstValueAssignment.putIfAbsent(inst.lhv, inst)
        }

        for (inst in assignInstructions) {
            val lhv = inst.lhv as? JIRRawSimpleValue ?: continue
            val rhv = inst.rhv as? JIRRawLocalVar ?: continue

            if (rhv in reservedValues) continue

            val rhvUsage = uses[rhv]?.singleOrNull() ?: continue
            if (rhvUsage != inst) continue

            val lhvUsage = uses[lhv]?.firstOrNull()

            if (lhvUsage != null && isBefore(instructionIndex, lhvUsage, inst)) continue

            val assignInstructionToReplacement = firstValueAssignment[lhv]
            val assignInstructionToRhv = firstValueAssignment[rhv]

            val didNotAssignedBefore =
                lhvUsage == null ||
                        assignInstructionToReplacement == null ||
                        !isBefore(instructionIndex, assignInstructionToReplacement, lhvUsage) ||
                        assignInstructionToRhv == null ||
                        areSequential(instructionIndex, assignInstructionToRhv, inst)

            if (didNotAssignedBefore) {
                replacements[rhv] = lhv
                reservedValues += lhv
                replacedInsts += inst
            }
        }

        return replacements to replacedInsts
    }

    private fun isBefore(instIndex: Map<JIRRawInst, Int>, one: JIRRawInst, another: JIRRawInst): Boolean {
        val indexOfOne = instIndex[one] ?: error("Missed instruction: $one")
        val indexOfAnother = instIndex[another] ?: error("Missed instruction: $another")
        return indexOfOne < indexOfAnother
    }

    private fun areSequential(instIndex: Map<JIRRawInst, Int>, one: JIRRawInst, another: JIRRawInst): Boolean {
        val indexOfOne = instIndex[one] ?: error("Missed instruction: $one")
        val indexOfAnother = instIndex[another] ?: error("Missed instruction: $another")
        return indexOfOne + 1 == indexOfAnother
    }

    private fun computeAssignments(instList: JIRInstList<JIRRawInst>): Map<JIRRawLocalVar, Set<JIRRawExpr>> {
        val assignments = mutableMapOf<JIRRawLocalVar, MutableSet<JIRRawExpr>>()
        for (inst in instList) {
            if (inst is JIRRawAssignInst) {
                val lhv = inst.lhv
                val rhv = inst.rhv
                if (lhv is JIRRawLocalVar) {
                    assignments.getOrPut(lhv, ::mutableSetOf).add(rhv)
                }
            }
        }
        return assignments
    }

    private fun normalizeTypes(
        instList: JIRInstList<JIRRawInst>,
    ): JIRInstList<JIRRawInst> {
        val types = mutableMapOf<JIRRawLocalVar, MutableSet<String>>()
        for (inst in instList) {
            if (inst is JIRRawAssignInst && inst.lhv is JIRRawLocalVar && inst.rhv !is JIRRawNullConstant) {
                types.getOrPut(
                    inst.lhv as JIRRawLocalVar,
                    ::mutableSetOf
                ) += inst.rhv.typeName.typeName
            }
        }
        val replacement = types.filterValues { it.size > 1 }
            .mapValues {
                JIRRawLocalVar(it.key.index, it.key.name, it.key.typeName, it.key.kind)
            }
        return instList.map(ExprMapper(replacement.toMap()))
    }
}

private class SimplifierCollector : AbstractFullRawExprSetCollector() {
    val exprs = hashSetOf<JIRRawSimpleValue>()

    override fun ifMatches(expr: JIRRawExpr) {
        if (expr is JIRRawSimpleValue && expr !is JIRRawConstant) {
            exprs.add(expr)
        }
    }

}

private class RawLocalVarCollector(private val localVar: JIRRawValue) : AbstractFullRawExprSetCollector() {

    var hasVar = false

    override fun ifMatches(expr: JIRRawExpr) {
        if (!hasVar) {
            hasVar = expr is JIRRawValue && expr == localVar
        }
    }
}

fun JIRRawInst.hasExpr(variable: JIRRawValue): Boolean {
    return RawLocalVarCollector(variable).also {
        accept(it)
    }.hasVar
}
