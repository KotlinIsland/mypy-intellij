package org.basedsoft.plugins.mypy

import com.intellij.openapi.components.service
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.jetbrains.python.psi.PyCallable
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.types.PyType
import com.jetbrains.python.psi.types.PyTypeParser
import com.jetbrains.python.psi.types.PyTypeProviderBase
import com.jetbrains.python.psi.types.TypeEvalContext

@Suppress("unused")
class MypyTypeProvider : PyTypeProviderBase() {

    private fun handle(e: PsiElement): PyType? {
        val mypyService = e.project.service<MypyService>()
        return PyTypeParser.getTypeByName(e, mypyService.inspect(e) ?: return null)
    }

    override fun getReferenceType(
        referenceTarget: PsiElement,
        context: TypeEvalContext,
        anchor: PsiElement?
    ): Ref<PyType>? {
        return handle(referenceTarget)?.let { Ref.create(it) }
    }

    override fun getReturnType(callable: PyCallable, context: TypeEvalContext): Ref<PyType>? {
        return handle(callable)?.let { Ref.create(it) }
    }

//    override fun getParameterType(param: PyNamedParameter, func: PyFunction, context: TypeEvalContext): Ref<PyType>? {
//        return handle(param)?.let { Ref.create(it) }
//    }

    override fun getReferenceExpressionType(
        referenceExpression: PyReferenceExpression,
        context: TypeEvalContext
    ): PyType? {
        return handle(referenceExpression)
    }
}


//
//class R: com.jetbrains.python.psi.resolve.PyReferenceResolveProvider {
//    override fun resolveName(element: PyQualifiedExpression, context: TypeEvalContext): MutableList<RatedResolveResult> {
//        if (element.name != "reveal_type")
//            return mutableListOf()
//        return mutableListOf(ImportedResolveResult(PyBuiltinCache.getInstance(element).builtinsFile, RatedResolveResult.RATE_NORMAL, null))
//    }
//}
