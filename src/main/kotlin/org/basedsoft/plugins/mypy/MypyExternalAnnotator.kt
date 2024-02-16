package org.basedsoft.plugins.mypy

import com.google.gson.Gson
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.findParentOfType
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.siblings
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import com.intellij.ui.JBColor
import com.jetbrains.python.PythonLanguage
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyForPart
import com.jetbrains.python.psi.PyFunction
import java.awt.Color
import kotlin.io.path.*


class MypyExternalAnnotator : ExternalAnnotator<Info, AnnotationResult>() {
    override fun collectInformation(file: PsiFile): Info = Info(file, file.project.service<MypyService>())

    override fun doAnnotate(collectedInfo: Info?): AnnotationResult? {
        val project = collectedInfo!!.file.project
        if (!collectedInfo.mypyService.checkEnv()) {
            return null
        }
        val (outputLines, useTemp) = collectedInfo.mypyService.run(Run, collectedInfo.file)
        val filePath = collectedInfo.file.virtualFile.path.toNioPathOrNull()!!.relativeTo(Path(project.basePath!!))
        val lineRegex = Regex("""^(.*?):(\d+):(\d+):(?:(\d+):(\d+):)? (\w+): (\S.*?)(?: {2}\[([\w-]+)])?$""")
        val lookFor = if (useTemp) "$tempFile:" else filePath.toString()
        // TODO: get the actual module, not just the file name
        val module = filePath.nameWithoutExtension
        return AnnotationResult(outputLines.mapNotNull { line ->
            if (line.startsWith(lookFor)) lineRegex.find(line)
                ?.let {
                    ResultItem(
                        it.groups[2]!!.value.toInt(),
                        it.groups[3]!!.value.toInt(),
                        it.groups[4]?.value?.toInt() ?: 0,
                        it.groups[5]?.value?.toInt() ?: 0,
                        getSeverity(it.groups[6]!!.value),
                        it.groups[7]!!.value.replace(tempFile.nameWithoutExtension, module),
                        it.groups[8]?.value,
                    )
                }
            else null
        }.distinct())
    }

    private fun getSeverity(severity: String): HighlightSeverity = when (severity) {
        "warning" -> HighlightSeverity.WARNING
        "error" -> HighlightSeverity.ERROR
        "note" -> HighlightSeverity.WARNING
        "baseline" -> baselineSeverity
        else -> throw Exception("Unknown severity: $severity")
    }

    override fun apply(file: PsiFile, annotationResult: AnnotationResult?, holder: AnnotationHolder) {
        val (baselines, items) = doBaseline(file.virtualFile.path, file.project).let { baselines ->
            val items = annotationResult?.items ?: return@let baselines to listOf()
            // filter baselines to those that hit actual errors
            val baselinesToShow = baselines.filter { baseline ->
                items.any { it.similar(baseline) }
            }
            // filter items to those that don't hit baselines
            val itemsToShow = items.filter { item ->
                baselinesToShow.none { item.similar(it) }
            }
            baselinesToShow to itemsToShow
        }
        baselines.forEach { item ->
            val startOffset: Int
            try {
                startOffset = file.viewProvider.document!!.getLineStartOffset(item.lineStart - 1) + item.columnStart - 1
            } catch (_: IndexOutOfBoundsException) {
                return@forEach
            }
            val element = (file.findElementAt(startOffset) ?: return@forEach)
                .let { if (it is LeafPsiElement && it.parent !is PyFunction && it.parent !is PyForPart) it.parent else it }
//            if (element is PyFile || element is PsiWhiteSpace) {
            if (element is PyFile) {
                return@forEach
            }
            val annotation = holder.newAnnotation(item.severity, item.fullMessage).range(element)
            annotation.newFix(SuppressIntention(item)).registerFix().enforcedTextAttributes(baselineTextAttributes)
            SuggestIntention.create(file, startOffset, annotation, item)
            annotation.create()
        }
        // TODO: combine notes into errors

        items.forEach { item ->
            val startLineOffset: Int
            val startLineEndOffset: Int

            try {
                startLineOffset = file.viewProvider.document!!.getLineStartOffset(item.lineStart - 1)
                startLineEndOffset = file.viewProvider.document!!.getLineEndOffset(item.lineStart - 1)
            } catch (_: IndexOutOfBoundsException) {
                // happens when the file changes between collect and apply
                return
            }
            val startOffset = startLineOffset + item.columnStart - 1

            val endOffset = if (item.lineStart == item.lineEnd) startLineOffset + item.columnEnd
            else {
                val psiElement = file.viewProvider.findElementAt(startLineEndOffset - 1, PythonLanguage.getInstance())
                if (psiElement is PsiComment) {
                    psiElement.siblings(forward = false, withSelf = false).first { it !is PsiWhiteSpace }.endOffset
                } else startLineEndOffset
            }
            if (endOffset > file.endOffset) return@forEach
            val annotation = holder.newAnnotation(item.severity, item.fullMessage).range(TextRange(startOffset, endOffset))
            SuggestIntention.create(file, startOffset, annotation, item)
            if (item.code != null) {
                annotation.newFix(SuppressIntention(item)).registerFix()
            } else
                annotation.enforcedTextAttributes(TextAttributes().apply {
                    effectType = EffectType.WAVE_UNDERSCORE; effectColor = JBColor.BLUE
                })
            annotation.create()
        }
    }
}

val baselineSeverity = HighlightSeverity("Static Info", 10, { "static info" }, { "Static Info" }, { "Static Info" })

val baselineTextAttributes = TextAttributes().apply {
    effectType = EffectType.LINE_UNDERSCORE
    effectColor = JBColor.lazy {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val b = scheme.defaultBackground
        val f = scheme.defaultForeground
        val factor = .5
        @Suppress("UseJBColor") (Color(
            (b.red - (b.red - f.red) * factor).toInt(),
            (b.green - (b.green - f.green) * factor).toInt(),
            (b.blue - (b.blue - f.blue) * factor).toInt(),
        ))
    }
}

class Info(val file: PsiFile, val mypyService: MypyService)
class AnnotationResult(val items: List<ResultItem>)

data class ResultItem(
    val lineStart: Int,
    val columnStart: Int,
    val lineEnd: Int,
    val columnEnd: Int,
    val severity: HighlightSeverity,
    val message: String,
    val code: String?,
    val baseline: Boolean = false,
) {
    fun similar(other: ResultItem) = lineStart == other.lineStart && columnStart == other.columnStart && message == other.message && code == other.code
    val fullMessage
        get() = buildString { if (baseline) append("baselined: "); append(message); if (code != null) append(" [$code]") }
}

class SuppressIntention(private val result: ResultItem) : IntentionAction {
    override fun startInWriteAction() = true

    override fun getText() = "Suppress error"

    override fun getFamilyName() = "Amon gus"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(file!!)!!
        val eol = document.getLineEndOffset(result.lineStart - 1)
        val psiElement = file.viewProvider.findElementAt(eol - 1, PythonLanguage.getInstance())
        val (offset, message) = if (psiElement is PsiComment) {
            val commentText = psiElement.text
            if (commentText matches Regex("""^#\s*type\s*:\s*ignore.*""")) {
                // The text of the PsiComment already has an ignore comment
                val insertPosition = commentText.lastIndexOf("]")
                if (insertPosition != -1) psiElement.startOffset + insertPosition to ", ${result.code}"
                else
                // If the closing tag is not found
                    psiElement.startOffset + commentText.indexOf("ignore") + 6 to "[${result.code}]"
            } else psiElement.prevSibling.endOffset to "# type: ignore[${result.code}]  "
        } else eol to "  # type: ignore[${result.code}]"
        document.insertString(offset, message)
        documentManager.commitDocument(document)
    }
}

class SuggestIntention(private val result: ResultItem, private val name: String) : IntentionAction {
    override fun startInWriteAction() = true

    override fun getText() = "Suggest type from usages"

    override fun getFamilyName() = "Amon gus"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = file!!.viewProvider.document!!
        val sol = document.getLineStartOffset(result.lineStart - 1)
        val func = file.findElementAt(sol + result.columnStart)!!.findParentOfType<PyFunction>()!!
        val new = project.service<MypyService>().suggest(file, name)
        val data = Gson().fromJson(new.trim('[', ']'), Map::class.java)["signature"] as Map<*, *>
        val argTypes = data["arg_types"] as List<*>
        val returnType = data["return_type"]
        val returnString = if(returnType == "None") ""  else " -> $returnType"
        if (func.annotation != null)
            document.replaceString(func.parameterList.endOffset, func.annotation!!.endOffset, returnString)
        else
            document.insertString(func.parameterList.endOffset, returnString)
        for ((parameter, type) in func.parameterList.parameters.zip(argTypes).reversed()) {
            val named = parameter.asNamed!!
            if (named.annotation != null)
                document.replaceString(named.annotation!!.startOffset, named.annotation!!.endOffset, ": $returnType")
            else
                document.insertString(named.nameIdentifier!!.endOffset, ": $type")
        }
        documentManager.commitDocument(document)
    }
    companion object {
        fun create(file: PsiFile, startOffset: Int, annotation: AnnotationBuilder, item: ResultItem) {
            val parentFunction = file.findElementAt(startOffset)?.parentOfType<PyFunction>() ?: return
            val name = parentFunction.qualifiedName ?: return
            if (startOffset <= (parentFunction.annotation?.endOffset
                    ?: parentFunction.parameterList.endOffset)
            )
                annotation.newFix(SuggestIntention(item, name)).registerFix()
        }
    }
}


/**
 * This is a not-good one, needs to be implemented in basedmypy
 */
fun doBaseline(file: String, project: Project) = buildList {
    val path = Path(project.basePath ?: return emptyList<ResultItem>(), ".mypy/baseline.json")
    if (!path.exists()) return emptyList<ResultItem>()
    val data = Gson().fromJson(path.readText(), Map::class.java)
    val relPath = try {
        Path(file).relativeTo(Path(project.basePath!!)).pathString.replace("\\", "/")
    } catch (e: IllegalArgumentException) {
        return emptyList<ResultItem>()
    }
    val errors = (data["files"] as Map<String, List<Map<String, Any>>>)[relPath] ?: return emptyList<ResultItem>()
    var line = 0
    for (error in errors) {
        line += (error["offset"] as Double).toInt()
        val column: Int by error
        val message: String by error
        val code: String by error
        add(
            ResultItem(
                line,
                column + 1,
                0,
                0,
                baselineSeverity,
                message,
                code,
                baseline = true
            )
        )
    }
}

@Suppress("unused")
fun PsiFile.inFunctionSignature(offset: Int): Boolean {
    val function = findElementAt(offset)?.parentOfType<PyFunction>() ?: return false
    return offset <= (function.annotation?.endOffset ?: function.parameterList.endOffset)
}