package org.seqra.ir.impl.types.signature

import org.seqra.ir.api.jvm.*

internal class FieldResolutionImpl(val fieldType: JvmType) : FieldResolution

internal class RecordComponentResolutionImpl(val recordComponentType: JvmType) : RecordComponentResolution

internal class MethodResolutionImpl(
    val returnType: JvmType,
    val parameterTypes: List<JvmType>,
    val exceptionTypes: List<JvmRefType>,
    val typeVariables: List<JvmTypeParameterDeclaration>
) : MethodResolution

internal class TypeResolutionImpl(
    val superClass: JvmType,
    val interfaceType: List<JvmType>,
    val typeVariables: List<JvmTypeParameterDeclaration>
) : TypeResolution
