
@file:JvmName("JIRMethods")
package org.seqra.ir.api.jvm.ext

import org.seqra.ir.api.jvm.JIRField
import org.seqra.ir.api.jvm.JIRMethod
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import java.util.*


const val CONSTRUCTOR = "<init>"

/**
 * is method has `strictfp` modifier
 */
val JIRMethod.isStrict: Boolean
    get() {
        return access and Opcodes.ACC_STRICT != 0
    }

val JIRMethod.jvmSignature: String
    get() {
        return name + description
    }

val JIRMethod.jIRdbSignature: String
    get() {
        val params = parameters.joinToString(";") { it.type.typeName } + (";".takeIf { parameters.isNotEmpty() } ?: "")
        return "$name($params)${returnType.typeName};"
    }

val JIRMethod.humanReadableSignature: String
    get() {
        val params = parameters.joinToString(",") { it.type.typeName }
        return "${enclosingClass.name}#$name($params):${returnType.typeName}"
    }

@get:JvmName("hasBody")
val JIRMethod.hasBody: Boolean
    get() {
        return !isNative && !isAbstract && withAsmNode { it.instructions.first != null }
    }


val JIRMethod.usedMethods: List<JIRMethod>
    get() {
        val cp = enclosingClass.classpath
        val result = LinkedHashSet<JIRMethod>()
        withAsmNode { methodNode ->
            methodNode.instructions.forEach { instruction ->
                when (instruction) {
                    is MethodInsnNode -> {
                        val owner = Type.getObjectType(instruction.owner).className
                        val clazz = cp.findClassOrNull(owner)
                        if (clazz != null) {
                            clazz.findMethodOrNull(instruction.name, instruction.desc)?.also {
                                result.add(it)
                            }
                        }
                    }
                }
            }
        }
        return result.toList()
    }

class FieldUsagesResult(
    val reads: List<JIRField>,
    val writes: List<JIRField>
)

/**
 * find all methods used in bytecode of specified `method`
 * @param method method to analyze
 */
val JIRMethod.usedFields: FieldUsagesResult
    get() {
        val cp = enclosingClass.classpath
        val reads = LinkedHashSet<JIRField>()
        val writes = LinkedHashSet<JIRField>()
        withAsmNode { methodNode ->
            methodNode.instructions.forEach { instruction ->
                when (instruction) {
                    is FieldInsnNode -> {
                        val owner = Type.getObjectType(instruction.owner).className
                        val clazz = cp.findClassOrNull(owner)
                        if (clazz != null) {
                            val jIRClass = clazz.findFieldOrNull(instruction.name)
                            if (jIRClass != null) {
                                when (instruction.opcode) {
                                    Opcodes.GETFIELD -> reads.add(jIRClass)
                                    Opcodes.GETSTATIC -> reads.add(jIRClass)
                                    Opcodes.PUTFIELD -> writes.add(jIRClass)
                                    Opcodes.PUTSTATIC -> writes.add(jIRClass)
                                }
                            }
                        }
                    }
                }
            }
        }
        return FieldUsagesResult(
            reads = reads.toList(),
            writes = writes.toList()
        )
    }
