package org.basedsoft.plugins.mypy

import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.suggested.endOffset
import com.intellij.refactoring.suggested.startOffset
import com.intellij.util.DocumentUtil
import com.intellij.util.ResourceUtil
import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText

@Service(Service.Level.PROJECT)
class MypyService(private val project: Project) {
    private var previousTemp: String? = null

    private val queue = mutableSetOf<String>()

    private val timestampMap = mutableMapOf<String, Long>()

    private val previousMap = mutableMapOf<String, String>()

    private lateinit var mypyHandler: Process

    private fun checkHandler() {
        // TODO: bundled version of mypy
        if (::mypyHandler.isInitialized && mypyHandler.isAlive)
            return
        val exec = ProjectRootManager.getInstance(project).projectSdk?.homePath ?: throw NoEnvironmentException()
        val projectPath = File(project.basePath!!)
        val handler = ResourceUtil.getResourceAsBytes(
            "org/basedsoft/plugins/mypy/mypy-handler.py",
            MypyService::class.java.classLoader
        )!!
            .decodeToString().let {
                if (SystemInfo.isWindows) it.replace("\"", "\\\"") else it
            }
        mypyHandler = ProcessBuilder(exec, "-uc", handler).apply {
            directory(projectPath)
            // TODO: proper logging
            redirectError(ProcessBuilder.Redirect.INHERIT)
        }.start()
    }


    fun checkEnv() = try {
        checkHandler()
        true
    } catch (_: NoEnvironmentException) {
        false
    }

    private inline fun <R> interact(block: () -> R) = synchronized(this, block)

    private fun doTemp(d: Document, realPath: String): Boolean {
        Path(project.basePath!!, tempFile.toString()).apply {
            if (previousTemp == null) {
                parent.createDirectories()
            }
            return if (d.modificationStamp == timestampMap[realPath]) {
                false
            } else {
                timestampMap[realPath] = d.modificationStamp
                val text = d.charsSequence.toString()
                if (text != previousTemp) {
                    previousTemp = text
                    writeText(text)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun send(vararg commands: Command) {
        checkHandler()
        with(mypyHandler.outputStream) {
            write(commands.joinToString(" ", postfix = "\n") { it.command }.toByteArray())
            flush()
        }
    }

    fun run(command: Command, file: PsiFile): Pair<List<String>, Boolean> = interact {
        val d = FileDocumentManager.getInstance().getCachedDocument(file.virtualFile)!!

        val useTemp =
            doTemp(d, file.virtualFile.path.toNioPathOrNull()!!.relativeTo(Path(project.basePath!!)).toString())
        val useCommand = if (command is Suggest && useTemp) {
            // TODO: this should be in Suggest
             Suggest("temp." + command.command.substringAfterLast(".").substringBefore("::"))
        } else command
        send(useCommand)
        return mypyHandler.inputStream.bufferedReader().lineSequence()
            .takeWhile { !it.startsWith('#') }
            .toList() to useTemp
    }

    private fun doInspect(location: String, check: Boolean = true): String {
        if (check) {
            send(Inspect(location), Run)
        } else
            send(Inspect(location))
        return mypyHandler.inputReader().readLine().orEmpty()
    }

    fun inspect(referenceTarget: PsiElement): String? {
        // TODO: last known good value for location for `x.`
        // TODO: get entire expression map instead
        val projectPath = Path(referenceTarget.project.basePath ?: return null)
        val d =
            FileDocumentManager.getInstance().getDocument(referenceTarget.containingFile?.virtualFile ?: return null)!!

        val range = referenceTarget.textRange ?: return null
        val lineStart = d.getLineNumber(range.startOffset) + 1
        val colStart = range.startOffset - DocumentUtil.getLineStartOffset(range.startOffset, d) + 1
        val lineEnd = d.getLineNumber(referenceTarget.endOffset) + 1
        val colEnd = range.endOffset - DocumentUtil.getLineStartOffset(range.endOffset, d)
        d.getLineNumber(referenceTarget.startOffset)

        val realPath = Path(referenceTarget.containingFile.virtualFile.path).relativeTo(projectPath).toString()
        val testPath = (if (FileDocumentManager.getInstance().isDocumentUnsaved(d))
            tempFile
        else
            realPath).toString()

        // TODO all files
        if (realPath.startsWith("../")) {
            return ""
        }

        val realLocation = "$realPath:$lineStart:$colStart:$lineEnd:$colEnd"
        if (realLocation in queue) {
            // TODO: do we need to drop the existing one?
            println("skipped $realLocation dupe")
            return null
        }
//        if (queue.size > 10) {
//            println("skipped $realLocation full")
//            return null
//        }
        queue.add(realLocation)
        interact {
            val testLocation: String
            val check: Boolean
            if (FileDocumentManager.getInstance().isDocumentUnsaved(d)) {
                check = doTemp(d, realPath)
                testLocation = "$testPath:$lineStart:$colStart:$lineEnd:$colEnd"
            } else {
                testLocation = realLocation
                if (d.modificationStamp == timestampMap[realPath]) {
                    check = false
                } else {
                    timestampMap[realPath] = d.modificationStamp
                    check = true
                }
            }
            if (!check) println("Skipping check for $realLocation")
            val result = doInspect(testLocation, check).trim('"', '\n', '\r')
                .ifEmpty { previousMap[realLocation] }.orEmpty()
            queue.remove(realLocation)
            previousMap[realLocation] = result
            println("done $realLocation")

            return result.takeUnless { it.startsWith("No known type available") || it.startsWith("Can't find expression") }
        }
    }

    fun suggest(file: PsiFile, functionName: String): String {
        return run(Suggest(functionName), file).first[0]
    }

}

class NoEnvironmentException : Exception()

sealed class Command(val command: String)
data object Run : Command("::run")
class Suggest(function: String) : Command("$function::suggest")
class Inspect(location: String) : Command("$location::inspect")

val tempFile = Path(".mypy_cache/__mypy_plugin_temp__.py")
