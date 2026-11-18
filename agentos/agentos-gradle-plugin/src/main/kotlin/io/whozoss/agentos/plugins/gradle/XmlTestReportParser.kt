package io.whozoss.agentos.plugins.gradle

import mu.KLogging
import org.w3c.dom.Element
import java.io.File
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.max

/**
 * Parses Gradle XML test reports and formats a human-readable summary.
 *
 * Extracted from [GradleTestTool] to be independently testable without spawning a Gradle process.
 */
internal class XmlTestReportParser {
    /**
     * Finds the `TEST-*.xml` reports under [reportsDir], the directory [TestReportsInitScript] points Gradle to
     * (the project own `build/test-results` is ignored).
     */
    fun collect(reportsDir: File): List<File> =
        reportsDir.walkTopDown().filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }.toList()

    /** Deletes [reportsDir], which belongs to one call only: other calls have their own. */
    fun delete(reportsDir: File) {
        reportsDir.deleteRecursively()
    }

    /**
     * Summarises [reports]. When the build failed ([exitCode] not 0) and the reports do not explain it (no failed
     * test, or a report that could not be read), the Gradle [rawOutput] is appended: with several modules, one may
     * have failed to compile or matched no test while the others left reports.
     */
    fun buildSummary(
        exitCode: Int,
        reports: List<File>,
        rawOutput: String = "",
    ): String {
        var totalTests = 0
        var totalSkipped = 0
        var totalFailures = 0
        var totalErrors = 0
        var unreadable = 0
        val failures = mutableListOf<FailureDetail>()

        val dbf = secureDocumentBuilderFactory()
        for (report in reports) {
            try {
                val doc = dbf.newDocumentBuilder().parse(report)
                val suite = doc.documentElement
                totalTests += suite.getAttribute("tests").toIntOrNull() ?: 0
                totalSkipped += suite.getAttribute("skipped").toIntOrNull() ?: 0
                totalFailures += suite.getAttribute("failures").toIntOrNull() ?: 0
                totalErrors += suite.getAttribute("errors").toIntOrNull() ?: 0

                val testcases = suite.getElementsByTagName("testcase")
                for (i in 0 until testcases.length) {
                    val tc = testcases.item(i) as Element
                    val failureNode = tc.getElementsByTagName("failure").item(0) as? Element
                    val errorNode = tc.getElementsByTagName("error").item(0) as? Element
                    val problemNode = failureNode ?: errorNode ?: continue

                    failures +=
                        FailureDetail(
                            className = tc.getAttribute("classname").substringAfterLast('.'),
                            methodName = tc.getAttribute("name"),
                            type = problemNode.getAttribute("type").trim(),
                            message = problemNode.getAttribute("message").trim(),
                            stack = problemNode.textContent.relevantStack(),
                        )
                }
            } catch (e: Exception) {
                unreadable++
                logger.warn { "Could not parse report ${report.name}: ${e.message}" }
            }
        }

        val passed = totalTests - totalSkipped - totalFailures - totalErrors
        val status = if (exitCode == 0) "SUCCESS" else "FAILURE"

        return buildString {
            appendLine("$status (exit $exitCode)")
            appendLine("Tests: $totalTests  Passed: $passed  Skipped: $totalSkipped  Failed: ${totalFailures + totalErrors}")
            if (unreadable > 0) appendLine("$unreadable report(s) could not be read")
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine("── Failures ──────────────────────────────────────────")
                // Identical failures (same message, same head frame) are listed once: one root cause, N tests.
                val groups = failures.groupBy { it.message to it.stack.firstOrNull() }.values.toList()
                // The counts above are exact; the list is bounded so many distinct failures cannot flood the agent context.
                val shown = groups.take(MAX_LISTED_FAILURES)
                // Few failures: full detail. Many: a smaller share of the budget each.
                val messageCap = max(MIN_MESSAGE_CHARS, TOTAL_MESSAGE_BUDGET / shown.size)
                shown.forEachIndexed { idx, group ->
                    val f = group.first()
                    appendLine()
                    appendLine("${idx + 1}. ${if (group.size == 1) f.title else "×${group.size} : ${group.joinToString { it.title }}"}")
                    if (f.type.isNotBlank()) appendLine("   type    : ${f.type}")
                    if (f.message.isNotBlank()) appendLine("   message : ${f.message.capped(messageCap).indented()}")
                    if (f.stack.isNotEmpty()) appendLine("   stack   : ${f.stack.joinToString("\n").indented()}")
                }
                if (groups.size > shown.size) {
                    appendLine()
                    appendLine("… ${groups.size - shown.size} more distinct failure(s) not shown (listed: $MAX_LISTED_FAILURES)")
                }
            }
            if (exitCode != 0 && (failures.isEmpty() || unreadable > 0) && rawOutput.isNotBlank()) {
                appendLine()
                appendLine("── Raw output ────────────────────────────────────────")
                appendLine(rawOutput)
            }
        }.trimEnd()
    }

    private fun String.capped(max: Int): String = if (length <= max) this else take(max) + "\n… (${length - max} chars truncated)"

    /** Aligns continuation lines under the value after the 13 chars label (`   message : `). */
    private fun String.indented(): String = replace("\n", "\n" + " ".repeat(13))

    /**
     * The signal of a raw stack trace: up to [MAX_FRAMES] frames outside [NOISE_PREFIXES], then each `Caused by:`
     * with its first such frame, except the root cause (the last one) which gets up to [MAX_FRAMES].
     * When every frame is noise, the first raw one is kept so a location is never lost.
     */
    private fun String.relevantStack(): List<String> {
        val lines = lines().map { it.trim() }.filter { it.startsWith("at ") || it.startsWith(CAUSED_BY) }
        val head = lines.takeWhile { !it.startsWith(CAUSED_BY) }
        val causes =
            lines.drop(head.size).fold(emptyList<List<String>>()) { acc, line ->
                if (line.startsWith(CAUSED_BY)) acc + listOf(listOf(line)) else acc.dropLast(1) + listOf(acc.last() + line)
            }
        return head.signal(MAX_FRAMES) +
            causes.flatMapIndexed { i, cause ->
                listOf(cause.first()) + cause.drop(1).signal(if (i == causes.lastIndex) MAX_FRAMES else 1)
            }
    }

    private fun List<String>.signal(max: Int): List<String> = filterNot { it.isNoise() }.take(max).ifEmpty { take(1) }

    private fun String.isNoise(): Boolean = NOISE_PREFIXES.any { removePrefix("at ").startsWith(it) }

    /**
     * A JUnit XML report has no DOCTYPE: refusing it removes the external entities and DTDs, so a report (written by
     * test code, which runs freely) cannot make the parser read a file or call a URL. Such a report counts as unreadable.
     */
    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }

    internal data class FailureDetail(
        val className: String,
        val methodName: String,
        val type: String,
        val message: String,
        val stack: List<String>,
    ) {
        val title get() = "$className › $methodName"
    }

    companion object : KLogging() {
        private const val CAUSED_BY = "Caused by:"
        private const val MAX_FRAMES = 5
        private const val TOTAL_MESSAGE_BUDGET = 6000
        private const val MIN_MESSAGE_CHARS = 400

        /** Max distinct failures listed in a summary, whatever their number. */
        private const val MAX_LISTED_FAILURES = 50

        // shortcut: hardcoded, java.* also hides useful JDK frames (java.util.concurrent), upgrade if that hurts
        private val NOISE_PREFIXES =
            listOf(
                "org.junit",
                "kotlin.test",
                "io.kotest",
                "kotlinx.coroutines",
                "java.",
                "jdk.",
                "sun.",
                "org.gradle",
            )
    }
}
