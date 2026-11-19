package io.whozoss.agentos.plugins.gradle

/**
 * Parses Kotlin/Java compiler diagnostics from Gradle `testClasses` raw output.
 *
 * Supported formats:
 *
 * Kotlin (kotlinc 1.x):
 *   `e: /abs/path/to/File.kt:12:5: error message`
 *   `w: /abs/path/to/File.kt:12:5: warning message`
 *
 * Kotlin (kotlinc 2.x): `file://` prefix, no colon after the column:
 *   `e: file:///abs/path/to/File.kt:12:5 error message`
 *   `w: file:///abs/path/to/File.kt:12:5 warning message`
 *
 * Java (javac via Gradle):
 *   `/abs/path/to/File.java:12: error: message`
 *   `/abs/path/to/File.java:12: warning: message`
 *
 * Lines that match neither pattern are captured as raw lines for fallback display.
 */
internal class CompilerDiagnosticParser {
    // Kotlin: "e: /path:line:col: message" (1.x) or "e: file:///path:line:col message" (2.x), same for "w:"
    private val kotlinPattern =
        Regex("""^([ew]):\s+(?:file://)?(.+\.(?:kt|kts)):(\d+):(\d+):?\s+(.+)$""")

    // Java: "/path.java:line: error|warning: message"
    private val javaPattern =
        Regex("""^(.+\.java):(\d+):\s+(error|warning):\s+(.+)$""")

    fun parse(rawOutput: String): List<Diagnostic> {
        val diagnostics = mutableListOf<Diagnostic>()
        for (line in rawOutput.lines()) {
            val trimmed = line.trim()
            val kotlin = kotlinPattern.matchEntire(trimmed)
            if (kotlin != null) {
                val (level, file, lineNo, col, msg) = kotlin.destructured
                diagnostics +=
                    Diagnostic(
                        severity = if (level == "e") Severity.ERROR else Severity.WARNING,
                        file = file,
                        line = lineNo.toIntOrNull(),
                        column = col.toIntOrNull(),
                        message = msg.trim(),
                    )
                continue
            }
            val java = javaPattern.matchEntire(trimmed)
            if (java != null) {
                val (file, lineNo, level, msg) = java.destructured
                diagnostics +=
                    Diagnostic(
                        severity = if (level == "error") Severity.ERROR else Severity.WARNING,
                        file = file,
                        line = lineNo.toIntOrNull(),
                        column = null,
                        message = msg.trim(),
                    )
            }
        }
        return diagnostics
    }

    /**
     * Formats diagnostics into a human-readable string.
     *
     * When [fileFilter] is non-null, diagnostics from the target file are listed first (prominently),
     * followed by a count of diagnostics from other files.
     */
    fun buildSummary(
        exitCode: Int,
        diagnostics: List<Diagnostic>,
        rawOutput: String,
        fileFilter: String? = null,
    ): String {
        val status = if (exitCode == 0) "SUCCESS" else "FAILURE"
        val errors = diagnostics.count { it.severity == Severity.ERROR }
        val warnings = diagnostics.count { it.severity == Severity.WARNING }

        return buildString {
            appendLine("$status (exit $exitCode)")
            appendLine("Errors: $errors  Warnings: $warnings")

            if (diagnostics.isNotEmpty()) appendDiagnostics(diagnostics, fileFilter)

            // A failed build with no parsed error has a cause the parser did not see (dependency resolution, annotation
            // processing, another module...): warnings alone must not hide it.
            if (exitCode != 0 && errors == 0) {
                appendLine()
                appendLine("── Raw output ────────────────────────────────────────")
                appendLine(rawOutput.keepEnd())
            }
        }.trimEnd()
    }

    private fun StringBuilder.appendDiagnostics(
        diagnostics: List<Diagnostic>,
        fileFilter: String?,
    ) {
        val (targeted, others) =
            if (fileFilter != null) {
                val normalised = fileFilter.replace('\\', '/')
                diagnostics.partition { it.file.replace('\\', '/').endsWith(normalised) }
            } else {
                diagnostics to emptyList()
            }

        // Errors come first: the list is bounded, and a build with many warnings must not push its errors out of it.
        val primary = targeted.ifEmpty { diagnostics }.errorsFirst()
        val secondary = if (targeted.isNotEmpty()) others.errorsFirst() else emptyList()

        appendLine()
        appendLine("── Diagnostics ───────────────────────────────────────")
        // The counts above are exact; the list is bounded so a broken build cannot flood the agent context.
        val primaryShown = primary.take(MAX_LISTED_DIAGNOSTICS)
        val secondaryShown = secondary.take(MAX_LISTED_DIAGNOSTICS - primaryShown.size)
        primaryShown.forEach { d -> appendLine(d.format()) }

        if (secondary.isNotEmpty()) {
            val otherErrors = secondary.count { it.severity == Severity.ERROR }
            val otherWarnings = secondary.count { it.severity == Severity.WARNING }
            appendLine()
            appendLine("── Other files: $otherErrors error(s), $otherWarnings warning(s) ──")
            secondaryShown.forEach { d -> appendLine(d.format()) }
        }

        val hidden = primary.size + secondary.size - primaryShown.size - secondaryShown.size
        if (hidden > 0) {
            appendLine()
            appendLine("… $hidden more diagnostic(s) not shown (listed: $MAX_LISTED_DIAGNOSTICS)")
        }
    }

    /** Errors before warnings, each kept in the order the compiler printed them (the sort is stable). */
    private fun List<Diagnostic>.errorsFirst(): List<Diagnostic> = sortedBy { it.severity != Severity.ERROR }

    internal data class Diagnostic(
        val severity: Severity,
        val file: String,
        val line: Int?,
        val column: Int?,
        val message: String,
    ) {
        fun format(): String {
            val loc =
                buildString {
                    append(file)
                    if (line != null) append(":$line")
                    if (column != null) append(":$column")
                }
            return "[${severity.label}] $loc — $message"
        }
    }

    internal enum class Severity(
        val label: String,
    ) {
        ERROR("ERROR"),
        WARNING("WARN"),
    }

    private companion object {
        /** Max diagnostics listed in a summary, whatever their number. */
        const val MAX_LISTED_DIAGNOSTICS = 100
    }
}
