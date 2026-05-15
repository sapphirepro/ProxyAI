package ee.carlrobert.codegpt.toolwindow.agent

import com.intellij.openapi.project.Project
import ee.carlrobert.codegpt.agent.ToolName
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import ee.carlrobert.codegpt.toolwindow.agent.ui.descriptor.FileChangeSnapshot
import ee.carlrobert.codegpt.toolwindow.agent.ui.renderer.applyStringReplacement
import kotlinx.serialization.json.Json

internal class HistoricalFileChangeReconstructor(
    private val project: Project,
    private val replayJson: Json
) {

    private val latestKnownContentByFile = mutableMapOf<String, String>()

    fun createSnapshot(rawToolName: String, args: Any, rawArgs: String): FileChangeSnapshot? {
        return when (HistoricalRollbackCompatibility.resolveSupportedTool(rawToolName)) {
            ToolName.EDIT -> decodeEditArgs(rawToolName, args, rawArgs)?.let(::createEditSnapshot)
            ToolName.WRITE -> decodeWriteArgs(
                rawToolName,
                args,
                rawArgs
            )?.let(::createWriteSnapshot)

            else -> null
        }
    }

    fun applySuccessfulResult(rawToolName: String, args: Any, rawArgs: String, rawResult: String) {
        when (HistoricalRollbackCompatibility.resolveSupportedTool(rawToolName)) {
            ToolName.READ -> {
                val readArgs = HistoricalRollbackCompatibility.decodeReadArgs(
                    replayJson = replayJson,
                    rawToolName = rawToolName,
                    args = args,
                    rawArgs = rawArgs
                ) ?: return
                val filePath = HistoricalRollbackCompatibility.normalizeToolFilePath(
                    project.basePath,
                    readArgs.filePath
                ) ?: return
                val content = HistoricalRollbackCompatibility.decodeReadToolResultContent(rawResult)
                    ?: return
                latestKnownContentByFile[filePath] = content
            }

            ToolName.EDIT -> {
                val editArgs = HistoricalRollbackCompatibility.decodeEditArgs(
                    replayJson = replayJson,
                    rawToolName = rawToolName,
                    args = args,
                    rawArgs = rawArgs
                ) ?: return
                val filePath = HistoricalRollbackCompatibility.normalizeToolFilePath(
                    project.basePath,
                    editArgs.filePath
                ) ?: return
                val known = latestKnownContentByFile[filePath] ?: return
                if (!known.contains(editArgs.oldString)) return
                latestKnownContentByFile[filePath] = applyStringReplacement(
                    known,
                    editArgs.oldString,
                    editArgs.newString,
                    editArgs.replaceAll
                )
            }

            ToolName.WRITE -> {
                val writeArgs = HistoricalRollbackCompatibility.decodeWriteArgs(
                    replayJson = replayJson,
                    rawToolName = rawToolName,
                    args = args,
                    rawArgs = rawArgs
                ) ?: return
                val filePath = HistoricalRollbackCompatibility.normalizeToolFilePath(
                    project.basePath,
                    writeArgs.filePath
                ) ?: return
                latestKnownContentByFile[filePath] = writeArgs.content
            }

            else -> Unit
        }
    }

    private fun createEditSnapshot(args: EditTool.Args): FileChangeSnapshot? {
        val filePath =
            HistoricalRollbackCompatibility.normalizeToolFilePath(project.basePath, args.filePath)
                ?: return null
        val before = latestKnownContentByFile[filePath] ?: return null
        if (!before.contains(args.oldString)) return null
        val after = applyStringReplacement(before, args.oldString, args.newString, args.replaceAll)
        return FileChangeSnapshot(before, after)
    }

    private fun createWriteSnapshot(args: WriteTool.Args): FileChangeSnapshot? {
        val filePath =
            HistoricalRollbackCompatibility.normalizeToolFilePath(project.basePath, args.filePath)
                ?: return null
        val before = latestKnownContentByFile[filePath] ?: return null
        if (before == args.content) return null
        return FileChangeSnapshot(before, args.content)
    }

    private fun decodeEditArgs(rawToolName: String, args: Any, rawArgs: String): EditTool.Args? {
        return HistoricalRollbackCompatibility.decodeEditArgs(
            replayJson = replayJson,
            rawToolName = rawToolName,
            args = args,
            rawArgs = rawArgs
        )
    }

    private fun decodeWriteArgs(rawToolName: String, args: Any, rawArgs: String): WriteTool.Args? {
        return HistoricalRollbackCompatibility.decodeWriteArgs(
            replayJson = replayJson,
            rawToolName = rawToolName,
            args = args,
            rawArgs = rawArgs
        )
    }
}
