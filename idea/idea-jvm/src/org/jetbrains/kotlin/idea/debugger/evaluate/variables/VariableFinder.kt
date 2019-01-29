/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.evaluate.variables

import com.intellij.debugger.engine.evaluation.EvaluateExceptionUtil
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.jdi.LocalVariableProxyImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.openapi.diagnostic.Attachment
import com.sun.jdi.*
import org.jetbrains.kotlin.codegen.AsmUtil
import org.jetbrains.kotlin.codegen.AsmUtil.getCapturedFieldName
import org.jetbrains.kotlin.codegen.AsmUtil.getLabeledThisName
import org.jetbrains.kotlin.codegen.coroutines.CONTINUATION_VARIABLE_NAME
import org.jetbrains.kotlin.codegen.inline.INLINE_FUN_VAR_SUFFIX
import org.jetbrains.kotlin.codegen.inline.INLINE_TRANSFORMATION_SUFFIX
import org.jetbrains.kotlin.idea.debugger.*
import org.jetbrains.kotlin.idea.debugger.evaluate.ExecutionContext
import org.jetbrains.kotlin.idea.debugger.evaluate.LOG
import org.jetbrains.kotlin.idea.debugger.evaluate.compilation.CodeFragmentParameter
import org.jetbrains.kotlin.idea.debugger.evaluate.compilation.CodeFragmentParameter.*
import org.jetbrains.kotlin.idea.debugger.evaluate.compilation.DebugLabelPropertyDescriptorProvider
import org.jetbrains.kotlin.idea.util.application.runReadAction
import org.jetbrains.kotlin.idea.util.attachment.mergeAttachments
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.load.java.JvmAbi.LOCAL_VARIABLE_NAME_PREFIX_INLINE_ARGUMENT
import org.jetbrains.kotlin.load.java.JvmAbi.LOCAL_VARIABLE_NAME_PREFIX_INLINE_FUNCTION
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import kotlin.coroutines.Continuation
import org.jetbrains.org.objectweb.asm.Type as AsmType
import com.sun.jdi.Type as JdiType

class VariableFinder private constructor(private val context: ExecutionContext, private val frameProxy: StackFrameProxyImpl) {
    companion object {
        private const val USE_UNSAFE_FALLBACK = false

        val CONTINUATION_TYPE: AsmType = AsmType.getType(Continuation::class.java)

        val SUSPEND_LAMBDA_CLASSES = listOf(
            "kotlin.coroutines.jvm.internal.SuspendLambda",
            "kotlin.coroutines.jvm.internal.RestrictedSuspendLambda"
        )

        fun instance(context: ExecutionContext): VariableFinder? {
            val frameProxy = context.evaluationContext.frameProxy ?: return null
            return VariableFinder(context, frameProxy)
        }

        fun variableNotFound(context: EvaluationContextImpl, message: String): Exception {
            val frameProxy = context.frameProxy
            val location = frameProxy?.safeLocation()
            val scope = context.debugProcess.searchScope

            val locationText = location?.run { "Location: ${sourceName()}:${lineNumber()}" } ?: "No location available"

            val sourceName = location?.sourceName()
            val declaringTypeName = location?.declaringType()?.name()?.replace('.', '/')?.let { JvmClassName.byInternalName(it) }

            val sourceFile = if (sourceName != null && declaringTypeName != null) {
                DebuggerUtils.findSourceFileForClassIncludeLibrarySources(context.project, scope, declaringTypeName, sourceName, location)
            } else {
                null
            }

            val sourceFileText = runReadAction { sourceFile?.text }

            if (sourceName != null && sourceFileText != null) {
                val attachments = mergeAttachments(
                    Attachment(sourceName, sourceFileText),
                    Attachment("location.txt", locationText)
                )

                LOG.error(message, attachments)
            }

            return EvaluateExceptionUtil.createEvaluateException(message)
        }

        // org.jetbrains.kotlin.codegen.inline.MethodInliner.prepareNode
        private const val OUTER_THIS_FOR_INLINE = AsmUtil.THIS + '_'

        val inlinedThisRegex = getLocalVariableNameRegexInlineAware(OUTER_THIS_FOR_INLINE)

        private fun getCapturedVariableNameRegex(capturedName: String): Regex {
            val escapedName = Regex.escape(capturedName)
            val escapedSuffix = Regex.escape(INLINE_TRANSFORMATION_SUFFIX)
            return Regex("^$escapedName(?:$escapedSuffix)?$")
        }

        private fun getLocalVariableNameRegexInlineAware(name: String): Regex {
            val escapedName = Regex.escape(name)
            val escapedSuffix = Regex.escape(INLINE_FUN_VAR_SUFFIX)
            return Regex("^$escapedName(?:$escapedSuffix)*$")
        }

        fun getInlineDepth(variables: List<LocalVariableProxyImpl>): Int {
            val inlineFunVariables = variables
                .filter { it.name().startsWith(LOCAL_VARIABLE_NAME_PREFIX_INLINE_FUNCTION) }

            if (inlineFunVariables.isEmpty()) {
                return 0
            }

            val closestInlineFun = inlineFunVariables.maxBy { it.variable }!!.variable
            val inlineLambdaDepth = variables
                .count { it.name().startsWith(LOCAL_VARIABLE_NAME_PREFIX_INLINE_ARGUMENT) && it.variable > closestInlineFun }

            return maxOf(0, inlineFunVariables.size - inlineLambdaDepth)
        }

        fun getInlineDepth(variableName: String): Int {
            var endIndex = variableName.length
            var depth = 0

            val suffixLen = INLINE_FUN_VAR_SUFFIX.length
            while (endIndex >= suffixLen) {
                if (variableName.substring(endIndex - suffixLen, endIndex) != INLINE_FUN_VAR_SUFFIX) {
                    break
                }

                depth++
                endIndex -= suffixLen
            }

            return depth
        }
    }

    private val evaluatorValueConverter = EvaluatorValueConverter(context)

    sealed class VariableKind(val asmType: AsmType) {
        abstract fun capturedNameMatches(name: String): Boolean

        class Ordinary(val name: String, asmType: AsmType) : VariableKind(asmType) {
            private val capturedNameRegex = getCapturedVariableNameRegex(getCapturedFieldName(this.name))
            override fun capturedNameMatches(name: String) = capturedNameRegex.matches(name)
        }

        // TODO Support overloaded local functions
        class LocalFunction(val name: String, asmType: AsmType) : VariableKind(asmType) {
            @Suppress("ConvertToStringTemplate")
            override fun capturedNameMatches(name: String) = name == "$" + name
        }

        class UnlabeledThis(asmType: AsmType) : VariableKind(asmType) {
            override fun capturedNameMatches(name: String) =
                (name == AsmUtil.CAPTURED_RECEIVER_FIELD || name.startsWith(getCapturedFieldName(AsmUtil.LABELED_THIS_FIELD)))
        }

        class OuterClassThis(asmType: AsmType) : VariableKind(asmType) {
            override fun capturedNameMatches(name: String) = false
        }

        class FieldVar(val fieldName: String, asmType: AsmType) : VariableKind(asmType) {
            // Captured 'field' are not supported yet
            override fun capturedNameMatches(name: String) = false
        }

        class ExtensionThis(val label: String, asmType: AsmType) : VariableKind(asmType) {
            val parameterName = getLabeledThisName(label, AsmUtil.LABELED_THIS_PARAMETER, AsmUtil.RECEIVER_PARAMETER_NAME)
            val fieldName = getLabeledThisName(label, getCapturedFieldName(AsmUtil.LABELED_THIS_FIELD), AsmUtil.CAPTURED_RECEIVER_FIELD)

            private val capturedNameRegex = getCapturedVariableNameRegex(fieldName)
            override fun capturedNameMatches(name: String) = capturedNameRegex.matches(name)
        }
    }

    class Result(val value: Value?)

    private class NamedEntity(val name: String, val type: JdiType?, val value: () -> Value?) {
        companion object {
            fun of(field: Field, owner: ObjectReference): NamedEntity {
                return NamedEntity(field.name(), field.safeType()) { owner.getValue(field) }
            }

            fun of(variable: LocalVariableProxyImpl, frameProxy: StackFrameProxyImpl): NamedEntity {
                return NamedEntity(variable.name(), variable.safeType()) { frameProxy.getValue(variable) }
            }
        }
    }

    fun find(parameter: CodeFragmentParameter, asmType: AsmType): Result? {
        return when (parameter.kind) {
            Kind.ORDINARY -> findOrdinary(VariableKind.Ordinary(parameter.name, asmType))
            Kind.FAKE_JAVA_OUTER_CLASS -> frameProxy.thisObject()?.let { Result(it) }
            Kind.EXTENSION_RECEIVER -> findExtensionThis(VariableKind.ExtensionThis(parameter.name, asmType))
            Kind.LOCAL_FUNCTION -> findLocalFunction(VariableKind.LocalFunction(parameter.name, asmType))
            Kind.DISPATCH_RECEIVER -> findDispatchThis(VariableKind.OuterClassThis(asmType))
            Kind.COROUTINE_CONTEXT -> findCoroutineContext()
            Kind.FIELD_VAR -> findFieldVariable(VariableKind.FieldVar(parameter.name, asmType))
            Kind.DEBUG_LABEL -> findDebugLabel(parameter.name)
        }
    }

    private fun findOrdinary(kind: VariableKind.Ordinary): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Local variables – direct search
        findLocalVariable(variables, kind, kind.name)?.let { return it }

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        // Recursive search in captured this
        val containingThis = frameProxy.thisObject() ?: return null
        return findCapturedVariable(kind, containingThis)
    }

    private fun findFieldVariable(kind: VariableKind.FieldVar): Result? {
        val thisObject = frameProxy.thisObject() ?: return null
        val field = thisObject.referenceType().fieldByName(kind.fieldName) ?: return null
        return Result(thisObject.getValue(field))
    }

    private fun findLocalFunction(kind: VariableKind.LocalFunction): Result? {
        val variables = frameProxy.safeVisibleVariables()
        
        // Local variables – direct search, new convention
        val newConventionName = AsmUtil.LOCAL_FUNCTION_VARIABLE_PREFIX + kind.name
        findLocalVariable(variables, kind, newConventionName)?.let { return it }

        // Local variables – direct search, old convention (before 1.3.30)
        findLocalVariable(variables, kind, kind.name + "$")?.let { return it }
        
        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        // Recursive search in captured this
        val containingThis = frameProxy.thisObject() ?: return null
        return findCapturedVariable(kind, containingThis)
    }

    private fun findExtensionThis(kind: VariableKind.ExtensionThis): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Local variables – direct search
        findLocalVariable(variables, kind, kind.parameterName)?.let { return it }

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        // Recursive search in captured this
        val containingThis = frameProxy.thisObject()
        if (containingThis != null) {
            findCapturedVariable(kind, containingThis)?.let { return it }
        }

        @Suppress("ConstantConditionIf")
        if (USE_UNSAFE_FALLBACK) {
            // Find an unlabeled this with the compatible type
            findUnlabeledThis(VariableKind.UnlabeledThis(kind.asmType))?.let { return it }
        }

        return null
    }

    private fun findDispatchThis(kind: VariableKind.OuterClassThis): Result? {
        val containingThis = frameProxy.thisObject()
        if (containingThis != null) {
            findCapturedVariable(kind, containingThis)?.let { return it }
        }

        if (isInsideDefaultImpls()) {
            val variables = frameProxy.safeVisibleVariables()
            findLocalVariable(variables, kind, getCapturedFieldName(AsmUtil.THIS))?.let { return it }
        }

        val variables = frameProxy.safeVisibleVariables()
        val inlineDepth = getInlineDepth(variables)

        if (inlineDepth > 0) {
            variables.namedEntitySequence()
                .filter { it.name.matches(inlinedThisRegex) && getInlineDepth(it.name) == inlineDepth && kind.typeMatches(it.type) }
                .mapNotNull { it.unwrapAndCheck(kind) }
                .firstOrNull()
                ?.let { return it }
        }

        @Suppress("ConstantConditionIf")
        if (USE_UNSAFE_FALLBACK) {
            // Find an unlabeled this with the compatible type
            findUnlabeledThis(VariableKind.UnlabeledThis(kind.asmType))?.let { return it }
        }

        return null
    }

    private fun findDebugLabel(name: String): Result? {
        val markupMap = DebugLabelPropertyDescriptorProvider.getMarkupMap(context.evaluationContext.debugProcess)

        for ((value, markup) in markupMap) {
            if (markup.text == name) {
                return Result(value)
            }
        }

        return null
    }

    private fun findUnlabeledThis(kind: VariableKind.UnlabeledThis): Result? {
        val variables = frameProxy.safeVisibleVariables()

        // Recursive search in local receiver variables
        findCapturedVariableInReceiver(variables, kind)?.let { return it }

        val containingThis = frameProxy.thisObject() ?: return null
        return findCapturedVariable(kind, containingThis)
    }

    private fun findLocalVariable(variables: List<LocalVariableProxyImpl>, kind: VariableKind, name: String): Result? {
        val inlineDepth = getInlineDepth(variables)

        if (inlineDepth > 0) {
            val nameInlineAwareRegex = getLocalVariableNameRegexInlineAware(name)
            variables.namedEntitySequence()
                .filter { it.name.matches(nameInlineAwareRegex) && getInlineDepth(it.name) == inlineDepth && kind.typeMatches(it.type) }
                .mapNotNull { it.unwrapAndCheck(kind) }
                .firstOrNull()
                ?.let { return it }
        }

        variables.namedEntitySequence()
            .filter { it.name == name && kind.typeMatches(it.type) }
            .mapNotNull { it.unwrapAndCheck(kind) }
            .firstOrNull()
            ?.let { return it }

        return null
    }

    private fun isInsideDefaultImpls(): Boolean {
        val declaringType = frameProxy.safeLocation()?.declaringType() ?: return false
        return declaringType.name().endsWith(JvmAbi.DEFAULT_IMPLS_SUFFIX)
    }

    private fun findCoroutineContext(): Result? {
        val method = frameProxy.safeLocation()?.safeMethod() ?: return null
        val result = findCoroutineContextForLambda(method) ?: findCoroutineContextForMethod(method) ?: return null
        return Result(result)
    }

    private fun findCoroutineContextForLambda(method: Method): ObjectReference? {
        if (method.name() != "invokeSuspend" || method.signature() != "(Ljava/lang/Object;)Ljava/lang/Object;") {
            return null
        }

        val thisObject = frameProxy.thisObject() ?: return null
        val thisType = thisObject.referenceType()

        if (SUSPEND_LAMBDA_CLASSES.none { thisType.isSubtype(it) }) {
            return null
        }

        return findCoroutineContextForContinuation(thisObject)
    }

    private fun findCoroutineContextForMethod(method: Method): ObjectReference? {
        if (CONTINUATION_TYPE.descriptor + ")" !in method.signature()) {
            return null
        }

        val continuationVariable = frameProxy.safeVisibleVariableByName(CONTINUATION_VARIABLE_NAME) ?: return null
        val continuation = frameProxy.getValue(continuationVariable) as? ObjectReference ?: return null
        return findCoroutineContextForContinuation(continuation)
    }

    private fun findCoroutineContextForContinuation(continuation: ObjectReference): ObjectReference? {
        val continuationType = (continuation.referenceType() as? ClassType)
            ?.allInterfaces()?.firstOrNull { it.name() == Continuation::class.java.name }
            ?: return null

        val getContextMethod = continuationType
            .methodsByName("getContext", "()Lkotlin/coroutines/CoroutineContext;").firstOrNull()
            ?: return null

        return continuation.invokeMethod(context.thread, getContextMethod, emptyList(), context.invokePolicy) as? ObjectReference
    }

    private fun findCapturedVariableInReceiver(variables: List<LocalVariableProxyImpl>, kind: VariableKind): Result? {
        fun isReceiverOrPassedThis(name: String) =
            name.startsWith(AsmUtil.LABELED_THIS_PARAMETER)
                    || name == AsmUtil.RECEIVER_PARAMETER_NAME
                    || name == getCapturedFieldName(AsmUtil.THIS)
                    || inlinedThisRegex.matches(name)

        if (kind is VariableKind.ExtensionThis) {
            variables.namedEntitySequence()
                .filter { kind.capturedNameMatches(it.name) && kind.typeMatches(it.type) }
                .mapNotNull { it.unwrapAndCheck(kind) }
                .firstOrNull()
                ?.let { return it }
        }

        return variables.namedEntitySequence()
            .filter { isReceiverOrPassedThis(it.name) }
            .mapNotNull { findCapturedVariable(kind, it.value()) }
            .firstOrNull()
    }

    private fun findCapturedVariable(kind: VariableKind, parent: Value?): Result? {
        val acceptsParentValue = kind is VariableKind.UnlabeledThis || kind is VariableKind.OuterClassThis
        if (parent != null && acceptsParentValue && kind.typeMatches(parent.type())) {
            return Result(parent)
        }

        val fields = (parent as? ObjectReference)?.referenceType()?.fields() ?: return null

        if (kind !is VariableKind.OuterClassThis) {
            // Captured variables - direct search
            fields.namedEntitySequence(parent)
                .filter { kind.capturedNameMatches(it.name) && kind.typeMatches(it.type) }
                .mapNotNull { it.unwrapAndCheck(kind) }
                .firstOrNull()
                ?.let { return it }

            // Recursive search in captured receivers
            fields.namedEntitySequence(parent)
                .filter { isCapturedReceiverFieldName(it.name) }
                .mapNotNull { findCapturedVariable(kind, it.value()) }
                .firstOrNull()
                ?.let { return it }
        }

        // Recursive search in outer and captured this
        fields.namedEntitySequence(parent)
            .filter { it.name == getCapturedFieldName(AsmUtil.THIS) || it.name == AsmUtil.CAPTURED_THIS_FIELD }
            .mapNotNull { findCapturedVariable(kind, it.value()) }
            .firstOrNull()
            ?.let { return it }

        return null
    }

    private fun isCapturedReceiverFieldName(name: String): Boolean {
        return name.startsWith(getCapturedFieldName(AsmUtil.LABELED_THIS_FIELD))
                || name == AsmUtil.CAPTURED_RECEIVER_FIELD
    }

    private fun VariableKind.typeMatches(actualType: JdiType?): Boolean {
        return evaluatorValueConverter.typeMatches(asmType, actualType)
    }

    private fun NamedEntity.unwrapAndCheck(kind: VariableKind): Result? {
        return evaluatorValueConverter.coerce(value(), kind.asmType)
    }

    private fun List<Field>.namedEntitySequence(owner: ObjectReference): Sequence<NamedEntity> {
        return asSequence().map { NamedEntity.of(it, owner) }
    }

    private fun List<LocalVariableProxyImpl>.namedEntitySequence(): Sequence<NamedEntity> {
        return asSequence().map { NamedEntity.of(it, frameProxy) }
    }
}