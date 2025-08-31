package org.seqra.ir.impl.types

import org.seqra.ir.api.jvm.*
import org.seqra.ir.api.jvm.ext.packageName
import org.seqra.ir.impl.bytecode.JIRAbstractLookup
import org.seqra.ir.impl.bytecode.PolymorphicSignatureSupport

class JIRClassTypeLookupImpl(val type: JIRClassType) : JIRLookup<JIRTypedField, JIRTypedMethod> {

    override fun field(name: String, typeName: TypeName?, fieldKind: JIRLookup.FieldKind): JIRTypedField? {
        return JIRClassTypeLookup.JIRTypedFieldLookup(type, name, fieldKind).lookup()
    }

    override fun method(name: String, description: String): JIRTypedMethod? {
        return JIRClassTypeLookup.JIRTypedMethodLookup(type, name, description).lookup()
    }

    override fun staticMethod(name: String, description: String): JIRTypedMethod? {
        return JIRClassTypeLookup.JIRStaticTypedMethodLookup(type, name, description).lookup()
    }

    override fun specialMethod(name: String, description: String): JIRTypedMethod? {
        return JIRClassTypeLookup.JIRSpecialTypedMethodLookup(type, name, description).lookup()
    }
}


abstract class JIRClassTypeLookup<Result : JIRAccessible>(clazz: JIRClassType) :
    JIRAbstractLookup<JIRClassType, Result>(clazz) {

    override val JIRClassType.resolvePackage: String
        get() = jIRClass.packageName


    internal open class JIRTypedMethodLookup(
        type: JIRClassType,
        protected val name: String,
        protected val description: String,
    ) : JIRClassTypeLookup<JIRTypedMethod>(type), PolymorphicSignatureSupport {

        override fun JIRClassType.next() = listOfNotNull(superType) + interfaces

        override val JIRClassType.elements: List<JIRTypedMethod>
            get() = declaredMethods

        override val predicate: (JIRTypedMethod) -> Boolean
            get() = { it.name == name && it.method.description == description }

        override fun lookupElement(en: JIRClassType): JIRTypedMethod? {
            return super.lookupElement(en) ?: en.declaredMethods.find(name)
        }
    }


    internal class JIRStaticTypedMethodLookup(
        type: JIRClassType,
        name: String,
        description: String,
    ) : JIRTypedMethodLookup(type, name, description) {

        override fun JIRClassType.next() = listOfNotNull(superType)

        override val predicate: (JIRTypedMethod) -> Boolean
            get() = { it.name == name && it.isStatic && it.method.description == description }

    }

    internal class JIRSpecialTypedMethodLookup(
        type: JIRClassType,
        name: String,
        description: String,
    ) : JIRTypedMethodLookup(type, name, description) {

        override fun JIRClassType.next() = listOfNotNull(superType)

        override val predicate: (JIRTypedMethod) -> Boolean
            get() = { it.name == name && it.method.description == description }

    }

    internal class JIRTypedFieldLookup(
        type: JIRClassType,
        private val name: String,
        private val kind: JIRLookup.FieldKind
    ) : JIRClassTypeLookup<JIRTypedField>(type) {

        override fun JIRClassType.next() = listOfNotNull(superType) + interfaces

        override val JIRClassType.elements: List<JIRTypedField>
            get() = declaredFields

        override val predicate: (JIRTypedField) -> Boolean
            get() = { it.name == name && it.matchKind() }

        private fun JIRTypedField.matchKind(): Boolean = when (kind) {
            JIRLookup.FieldKind.ANY -> true
            JIRLookup.FieldKind.STATIC -> isStatic
            JIRLookup.FieldKind.INSTANCE -> !isStatic
        }

    }

}
