package ee.carlrobert.codegpt.toolwindow.agent

import ee.carlrobert.codegpt.agent.ToolName
import ee.carlrobert.codegpt.agent.ToolSpecs
import ee.carlrobert.codegpt.agent.tools.EditTool
import ee.carlrobert.codegpt.agent.tools.ReadTool
import ee.carlrobert.codegpt.agent.tools.WriteTool
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Paths

internal object HistoricalRollbackCompatibility {

    private val supportedTools = setOf(ToolName.READ, ToolName.EDIT, ToolName.WRITE)

    fun resolveSupportedTool(rawToolName: String): ToolName? {
        val normalized = rawToolName.trim()
        if (normalized.isEmpty()) return null

        val resolved = ToolSpecs.find(normalized) ?: return null
        return resolved.takeIf { it in supportedTools }
    }

    fun isSuccessfulResult(toolName: ToolName, content: String, replayJson: Json): Boolean {
        if (toolName !in supportedTools) return false

        val normalized = content.trim()
        val decoded = ToolSpecs.decodeResultOrNull(toolName.id, normalized)
        if (decoded != null) {
            return when (toolName) {
                ToolName.READ -> decoded is ReadTool.Result.Success
                ToolName.EDIT -> decoded is EditTool.Result.Success
                ToolName.WRITE -> decoded is WriteTool.Result.Success
                else -> false
            }
        }

        // TODO: Is there a better way to determine if the result is successful?
        // This string comparison won't cut it
        return when (toolName) {
            ToolName.READ -> !normalized.startsWith(READ_ERROR_PREFIX, ignoreCase = true)
            ToolName.EDIT ->
                normalized.isNotBlank() &&
                        !normalized.startsWith(EDIT_ERROR_PREFIX, ignoreCase = true) &&
                        (normalized.contains(EDIT_SUCCESS_MARKER, ignoreCase = true) ||
                                normalized.contains(LEGACY_EDIT_SUCCESS_MARKER, ignoreCase = true))

            ToolName.WRITE ->
                normalized.isNotBlank() &&
                        normalized.contains(WRITE_SUCCESS_MARKER, ignoreCase = true) &&
                        !normalized.startsWith(WRITE_ERROR_PREFIX, ignoreCase = true)

            else -> false
        }
    }

    fun decodeReadArgs(
        replayJson: Json,
        rawToolName: String,
        args: Any? = null,
        rawArgs: String
    ): ReadTool.Args? {
        val typedArgs = args as? ReadTool.Args
        if (typedArgs != null) return typedArgs

        val decodedArgs = ToolSpecs.decodeArgsOrNull(rawToolName, rawArgs) as? ReadTool.Args
        if (decodedArgs != null) return decodedArgs

        val jsonArgs = parseToolArgs(replayJson, rawArgs) ?: return null
        val filePath = stringValue(jsonArgs["file_path"])
            ?: stringValue(jsonArgs["path"])
            ?: stringValue(jsonArgs["pathInProject"])
            ?: return null
        return ReadTool.Args(filePath = filePath)
    }

    fun decodeEditArgs(
        replayJson: Json,
        rawToolName: String,
        args: Any? = null,
        rawArgs: String
    ): EditTool.Args? {
        val typedArgs = args as? EditTool.Args
        if (typedArgs != null) return typedArgs

        val decodedArgs = ToolSpecs.decodeArgsOrNull(rawToolName, rawArgs) as? EditTool.Args
        if (decodedArgs != null) return decodedArgs

        val jsonArgs = parseToolArgs(replayJson, rawArgs) ?: return null
        val filePath = stringValue(jsonArgs["file_path"]) ?: return null
        val oldString = stringValue(jsonArgs["old_string"]) ?: return null
        val newString = stringValue(jsonArgs["new_string"]) ?: return null
        val shortDescription =
            stringValue(jsonArgs["short_description"]) ?: "Recovered historical edit"
        val replaceAll = booleanValue(jsonArgs["replace_all"]) ?: false
        return EditTool.Args(
            filePath = filePath,
            oldString = oldString,
            newString = newString,
            shortDescription = shortDescription,
            replaceAll = replaceAll
        )
    }

    fun decodeWriteArgs(
        replayJson: Json,
        rawToolName: String,
        args: Any? = null,
        rawArgs: String
    ): WriteTool.Args? {
        val typedArgs = args as? WriteTool.Args
        if (typedArgs != null) return typedArgs

        val decodedArgs = ToolSpecs.decodeArgsOrNull(rawToolName, rawArgs) as? WriteTool.Args
        if (decodedArgs != null) return decodedArgs

        val jsonArgs = parseToolArgs(replayJson, rawArgs) ?: return null
        val filePath = stringValue(jsonArgs["file_path"]) ?: return null
        val content = stringValue(jsonArgs["content"]) ?: return null
        return WriteTool.Args(filePath = filePath, content = content)
    }

    fun normalizeToolFilePath(projectBasePath: String?, rawPath: String?): String? {
        val trimmed = rawPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = trimmed.replace("\\", "/")
        val file = File(normalized)
        if (file.isAbsolute) {
            return file.toPath().normalize().toString().replace("\\", "/")
        }

        val basePath = projectBasePath ?: return file.absolutePath.replace("\\", "/")
        return Paths.get(basePath).resolve(normalized).normalize().toString().replace("\\", "/")
    }

    fun decodeReadToolResultContent(content: String): String? {
        if (content.isBlank()) return ""

        val numberedLines = content.lineSequence().mapNotNull { line ->
            val tabIndex = line.indexOf('\t')
            if (tabIndex <= 0) return@mapNotNull null
            val prefix = line.substring(0, tabIndex)
            if (!prefix.all { it.isDigit() }) return@mapNotNull null
            line.substring(tabIndex + 1)
        }.toList()
        if (numberedLines.isNotEmpty()) return numberedLines.joinToString(separator = "\n")

        if (content.startsWith(READ_ERROR_PREFIX, ignoreCase = true)) return null
        return content
    }

    private fun parseToolArgs(replayJson: Json, rawArgs: String): Map<String, JsonElement>? {
        return runCatching { replayJson.parseToJsonElement(rawArgs).jsonObject }.getOrNull()
    }

    private fun booleanValue(element: JsonElement?): Boolean? {
        val primitive = element as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content.toBooleanStrictOrNull() else primitive.booleanOrNull
    }

    private fun stringValue(element: JsonElement?): String? {
        if (element == null) return null
        val primitive = element as? JsonPrimitive
        return if (primitive != null && primitive.isString) primitive.content else element.toString()
    }

    private const val READ_ERROR_PREFIX = "Error reading file"
    private const val EDIT_ERROR_PREFIX = "Error editing file"
    private const val WRITE_ERROR_PREFIX = "Error writing file"
    private const val EDIT_SUCCESS_MARKER = "Successfully edited file"
    private const val LEGACY_EDIT_SUCCESS_MARKER = "Successfully made"
    private const val WRITE_SUCCESS_MARKER = "successfully"
}
