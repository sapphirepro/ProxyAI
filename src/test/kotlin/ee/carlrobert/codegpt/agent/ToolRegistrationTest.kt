package ee.carlrobert.codegpt.agent

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

class ToolRegistrationTest {

    @Test
    fun `all concrete tool files are registered in tool names`() {
        val registered = ToolName.entries
            .filterNot { it == ToolName.EXIT }
            .mapTo(linkedSetOf()) { normalize(it.displayName) }

        val discovered = sequenceOf(
            Path.of("src/main/kotlin/ee/carlrobert/codegpt/agent/tools"),
            Path.of("src/main/kotlin/ee/carlrobert/codegpt/agent/tools/ide")
        ).flatMap { root ->
            Files.list(root).use { paths ->
                paths
                    .filter { it.fileName.toString().endsWith("Tool.kt") }
                    .map { it.name.removeSuffix("Tool.kt") }
                    .filter { it !in ignoredToolFiles }
                    .toList()
            }.asSequence()
        }.mapTo(linkedSetOf(), ::normalize)

        assertThat(registered).containsAll(discovered)
    }

    private fun normalize(value: String): String {
        return value.lowercase().filter { it.isLetterOrDigit() }
    }

    private companion object {
        val ignoredToolFiles = setOf("Base")
    }
}
