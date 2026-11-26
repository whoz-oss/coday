package io.whozoss.agentos.plugins.ktlint

import java.nio.file.Path

/**
 * Port of the style engine, on single files. [KtlintRunner] is the ktlint adapter;
 * it is a seam so the time-boxing of the tools can be tested without a real slow engine.
 */
interface StyleRunner {
    /** Lists the violations of [file] without touching it. */
    fun lint(file: Path): List<KtlintViolation>

    /** Autocorrects [file] in place (written only when its content changes) and reports what was found. */
    fun format(file: Path): FormatResult
}

/**
 * Outcome of [StyleRunner.format] on one file.
 *
 * @property file name of the file, relative to the project root.
 * @property modified true when the file was rewritten.
 * @property correctedCount number of violations autocorrected.
 * @property remaining violations ktlint could not autocorrect.
 */
data class FormatResult(
    val file: String,
    val modified: Boolean,
    val correctedCount: Int,
    val remaining: List<KtlintViolation>,
)
