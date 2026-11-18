package io.whozoss.agentos.plugins.gradle

import java.io.File
import java.nio.file.Files

/**
 * Gradle init script (passed with `-I`) that makes every `Test` task write its JUnit XML reports under a
 * directory owned by the tool, `<reportsDir>/<project>-<hash of its dir>/<task>`, whatever the project config says.
 *
 * The directory is outside every project, so the reports of an included build (`includeBuild("../lib")`), which
 * lives anywhere on the disk, are found as well as those of the root build. An init script applies to the included
 * builds too.
 *
 * One directory per call ([newReportsDir]): concurrent calls never read or delete each other's results, and a call
 * never sees an older run. The new output location also makes Gradle rerun (or restore from the build cache)
 * instead of reporting UP-TO-DATE with no report. The script is a temp file, the project is never modified.
 */
internal object TestReportsInitScript {
    private fun content(reportsDir: File) =
        """
        allprojects {
            // afterEvaluate: the project own Test config must run first, so ours has the last word
            afterEvaluate {
                // project name + hash of its dir: unique even when two included builds have a root project of the same name
                def key = project.name + '-' + Integer.toHexString(project.projectDir.absolutePath.hashCode())
                def results = new File('${groovyLiteral(reportsDir.absolutePath)}', key)
                tasks.withType(Test).configureEach { t ->
                    t.reports.junitXml.required.set(true)
                    t.reports.junitXml.outputLocation.set(new File(results, t.name))
                }
            }
        }
        """.trimIndent()

    /** Escapes [value] for a single-quoted Groovy string. */
    private fun groovyLiteral(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    /** A new empty directory for the reports of one call. The caller deletes it once the reports are read. */
    fun newReportsDir(): File = Files.createTempDirectory("agentos-test-reports").toFile()

    /** The caller deletes the returned file once Gradle is done. */
    fun create(reportsDir: File): File =
        Files.createTempFile("agentos-test-reports", ".init.gradle").toFile().apply { writeText(content(reportsDir)) }
}
