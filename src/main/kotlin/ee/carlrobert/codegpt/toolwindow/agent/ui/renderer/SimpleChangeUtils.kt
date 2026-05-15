package ee.carlrobert.codegpt.toolwindow.agent.ui.renderer

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics2D
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

object ChangeColors {
    val inserted: JBColor = JBColor(Color(0x2E7D32), Color(0x81C784))
    val deleted: JBColor = JBColor(Color(0xC62828), Color(0xEF9A9A))
    val modified: JBColor = JBColor(Color(0x1565C0), Color(0x90CAF9))
}

data class DiffBadgeText(
    val inserted: String,
    val deleted: String,
    val changed: String,
    val summary: String
)

fun diffBadgeText(
    inserted: Int,
    deleted: Int,
    changed: Int,
    spaced: Boolean = true
): DiffBadgeText {
    val sep = if (spaced) " " else ""
    return DiffBadgeText(
        inserted = "+$inserted$sep",
        deleted = "-$deleted$sep",
        changed = "~$changed",
        summary = "+$inserted${sep}-$deleted${sep}~$changed"
    )
}

fun getFileContentWithFallback(path: String, charset: Charset = Charsets.UTF_8): String {
    return runCatching { Files.readString(Path.of(path), charset) }.getOrDefault("")
}

fun applyStringReplacement(
    original: String,
    oldString: String,
    newString: String,
    replaceAll: Boolean
): String {
    if (oldString.isEmpty()) return original
    return if (replaceAll) original.replace(oldString, newString) else original.replaceFirst(
        oldString,
        newString
    )
}

fun lineDiffStats(before: String, after: String): Triple<Int, Int, Int> {
    if (before == after) return Triple(0, 0, 0)
    return compareLineFragments(before, after).fold(Triple(0, 0, 0)) { (ins, del, mod), fragment ->
        val deletedLines = fragment.endLine1 - fragment.startLine1
        val insertedLines = fragment.endLine2 - fragment.startLine2
        val modifiedLines = minOf(deletedLines, insertedLines)
        Triple(
            ins + (insertedLines - modifiedLines),
            del + (deletedLines - modifiedLines),
            mod + modifiedLines
        )
    }
}

private fun compareLineFragments(before: String, after: String) = ComparisonManager.getInstance()
    .compareLines(
        before.replace("\r\n", "\n"),
        after.replace("\r\n", "\n"),
        ComparisonPolicy.DEFAULT,
        EmptyProgressIndicator()
    )

fun drawCenteredText(g2: Graphics2D, text: String, width: Int, height: Int) {
    val metrics = g2.fontMetrics
    val x = (width - metrics.stringWidth(text)) / 2
    val y = (height - metrics.height) / 2 + metrics.ascent
    g2.drawString(text, x, y)
}
