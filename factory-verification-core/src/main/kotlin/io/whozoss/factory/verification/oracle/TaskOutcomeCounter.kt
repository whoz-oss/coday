package io.whozoss.factory.verification.oracle

/**
 * Pure task-outcome counting over build output — ported faithfully from
 * `countTaskOutcomes` in `factory/src/domain/oracle/oracle.ts`.
 *
 * This function reads the output but renders NO verdict: a build entirely served
 * from the cache returns `exitCode: 0` without executing anything. Counting tasks
 * says what the verdict was about, not whether it is sound. The verdict remains
 * `exitCode == 0`.
 *
 * Recognises two grammars:
 *   - Gradle : `> Task :path:name [UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE]`
 *   - Nx     : `> nx run <project>:<target> [existing outputs match the cache, left as is]`
 *     plus the summary lines
 *     `Nx read the output from the cache ... for N out of M tasks.` and
 *     `NX   Successfully ran target <target> for N projects`.
 */
object TaskOutcomeCounter {

    private val ANSI = Regex("\u001b\\[[0-9;]*m")

    private val CACHE_SUMMARY = Regex(
        "Nx\\s+read\\s+the\\s+output\\s+from\\s+the\\s+cache\\s+instead\\s+of\\s+running\\s+the\\s+command\\s+for\\s+(\\d+)\\s+out\\s+of\\s+(\\d+)\\s+tasks",
    )

    private val SUCCESS_SUMMARY = Regex("NX\\s+Successfully\\s+ran\\s+target\\s+\\S+\\s+for\\s+(\\d+)\\s+projects?")

    /**
     * Counts task outcomes in a build output.
     *
     * @param output stdout + stderr concatenated.
     */
    fun countTaskOutcomes(output: String): TaskOutcomes {
        // Nx colours its output even when redirected. ANSI sequences are stripped
        // before analysis so line prefixes stay recognisable.
        val plain = ANSI.replace(output, "")
        val lines = plain.split("\n")

        var upToDate = 0
        var fromCache = 0
        var skipped = 0
        var executed = 0
        var nxTaskLines = 0

        var cacheSummaryFound = false
        var cacheSummaryFromCache: Int? = null
        var cacheSummaryTotal: Int? = null

        var successSummaryFound = false
        var successSummaryTotal: Int? = null

        for (line in lines) {
            // --- Gradle: `> Task :path:name [MARKER]`
            if (line.startsWith("> Task ")) {
                when {
                    line.contains("UP-TO-DATE") -> upToDate++
                    line.contains("FROM-CACHE") -> fromCache++
                    line.contains("SKIPPED") || line.contains("NO-SOURCE") -> skipped++
                    else -> executed++
                }
                continue
            }

            // --- Nx: `> nx run <project>:<target>` (cache marker on the same line)
            if (line.startsWith("> nx run ")) {
                nxTaskLines++
                if (line.contains("existing outputs match the cache")) fromCache++ else executed++
                continue
            }

            // --- Nx cache summary line
            val cacheMatch = CACHE_SUMMARY.find(line)
            if (cacheMatch != null) {
                cacheSummaryFound = true
                cacheSummaryFromCache = cacheMatch.groupValues[1].toIntOrNull() ?: 0
                cacheSummaryTotal = cacheMatch.groupValues[2].toIntOrNull() ?: 0
                continue
            }

            // --- Nx success summary line
            val successMatch = SUCCESS_SUMMARY.find(line)
            if (successMatch != null) {
                successSummaryFound = true
                successSummaryTotal = successMatch.groupValues[1].toIntOrNull() ?: 0
                continue
            }
        }

        val summaryFound = cacheSummaryFound || successSummaryFound
        val summaryFromCache = if (cacheSummaryFound) cacheSummaryFromCache else null
        val summaryTotal = when {
            cacheSummaryFound -> cacheSummaryTotal
            successSummaryFound -> successSummaryTotal
            else -> null
        }

        val summaryAbsenceReason: OracleSummaryAbsenceReason? =
            if (summaryFound) null else if (nxTaskLines == 0) OracleSummaryAbsenceReason.NO_NX_TASKS else OracleSummaryAbsenceReason.FRESH_RUN

        // Disagreement between independent measurements: signals a format change.
        // `false` when no summary line is present — an absence is not a disagreement.
        var countMismatch = false
        val lineTotal = upToDate + fromCache + skipped + executed
        if (cacheSummaryFound) {
            countMismatch = fromCache != cacheSummaryFromCache || lineTotal != cacheSummaryTotal
        }
        if (successSummaryFound) {
            countMismatch = countMismatch || lineTotal != successSummaryTotal
        }

        return TaskOutcomes(
            upToDate = upToDate,
            fromCache = fromCache,
            skipped = skipped,
            executed = executed,
            summaryFound = summaryFound,
            summaryAbsenceReason = summaryAbsenceReason,
            summaryFromCache = summaryFromCache,
            summaryTotal = summaryTotal,
            countMismatch = countMismatch,
        )
    }
}
