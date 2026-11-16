package io.whozoss.agentos.plugins.gradle

import java.io.File
import java.nio.file.Files

/**
 * Gradle init script (passed with `-I`) that makes every `Test` task write its JUnit XML reports to
 * `<module>/build/[REPORTS_DIR]/<runId>/<task>`, whatever the project config says.
 *
 * One dir per call: concurrent calls never read or delete each other's results, and a call never sees an
 * older run. The new output location also makes Gradle rerun (or restore from the build cache) instead of
 * reporting UP-TO-DATE with no report. The script is a temp file, the project is never modified.
 */
internal object TestReportsInitScript {
    const val REPORTS_DIR = "agentos-test-results"

    private fun content(runId: String) =
        """
        allprojects {
            // afterEvaluate: the project own Test config must run first, so ours has the last word
            afterEvaluate {
                def results = layout.buildDirectory.dir('$REPORTS_DIR/$runId')
                tasks.withType(Test).configureEach { t ->
                    t.reports.junitXml.required.set(true)
                    t.reports.junitXml.outputLocation.set(results.map { it.dir(t.name) })
                }
            }
        }
        """.trimIndent()

    /** The caller deletes the returned file once Gradle is done. */
    fun create(runId: String): File = Files.createTempFile("agentos-test-reports", ".init.gradle").toFile().apply { writeText(content(runId)) }
}
