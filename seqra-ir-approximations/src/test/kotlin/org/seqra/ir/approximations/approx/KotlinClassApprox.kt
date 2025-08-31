package org.seqra.ir.approximations.approx

import org.seqra.ir.approximation.annotation.Approximate
import org.seqra.ir.approximations.target.ClassForField
import org.seqra.ir.approximations.target.KotlinClass
import org.jetbrains.annotations.NotNull

@Approximate(KotlinClass::class)
class KotlinClassApprox {
    @NotNull
    private val artificialField: ClassForField = ClassForField()
    private val fieldToReplace: Int = 3
    private val sameApproximation: KotlinClassApprox? = null
    private val anotherApproximation: IntegerApprox? = null

    fun replaceBehaviour(value: Int): Int = 42

    fun artificialMethod(): Int = 1 + 2 * 3

    fun useArtificialField(classForField: ClassForField): Int {
        if (classForField == artificialField) {
            return fieldToReplace
        }

        return 0
    }

    fun useSameApproximationTarget(kotlinClass: KotlinClass): Int {
        if (sameApproximation == null) return 0

        if (kotlinClass.methodWithoutApproximation() == sameApproximation.artificialMethod()) {
            return 42
        }

        return 1
    }

    fun useAnotherApproximationTarget(value: Int): Int {
        if (anotherApproximation == null) return 0

        if (anotherApproximation.value == value) {
            return 42
        }

        return 1
    }

    fun useFieldWithoutApproximation(classForField: ClassForField): Int {
        if (classForField == artificialField) {
            return 1
        }

        return 2
    }
}