package io.whozoss.agentos.plugins.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import java.nio.file.Path

/**
 * Runs the embedded ktlint engine (standard ruleset) on single files.
 *
 * `.editorconfig` files are discovered by the engine from each file's location, so the target project's
 * own style configuration applies. Reported file names are relative to [projectRoot].
 *
 * @property projectRoot real path of the project root, used to relativize the reported file names.
 */
class KtlintRunner(
    private val projectRoot: Path,
) : StyleRunner {
    private val engine = KtLintRuleEngine(ruleProviders = StandardRuleSetProvider().getRuleProviders())

    /** @see StyleRunner.lint */
    override fun lint(file: Path): List<KtlintViolation> {
        val violations = mutableListOf<KtlintViolation>()
        engine.lint(code = codeOf(file = file)) { lintError ->
            violations += lintError.toViolation(file = file)
        }
        return violations
    }

    /**
     * @see StyleRunner.format
     *
     * The file is read and written with non-interruptible I/O ([java.io.File.readText] and [java.io.File.writeText],
     * plain streams): the worker thread may already be interrupted when it gets here (cancellation or timeout, the
     * engine does not look at interruption while it parses), and an interruptible channel would then fail after
     * having truncated the file, leaving it empty.
     */
    override fun format(file: Path): FormatResult {
        val original = file.toFile().readText(charset = Charsets.UTF_8)
        val findings = mutableListOf<Pair<KtlintViolation, Boolean>>()
        val formatted =
            engine.format(code = codeOf(file = file)) { lintError, corrected ->
                findings += lintError.toViolation(file = file) to corrected
            }
        val modified = formatted != original
        if (modified) {
            // Not Files.writeString: it truncates the file, then writes through an interruptible FileChannel; on an
            // interrupted thread the write fails with ClosedByInterruptException and the file stays empty.
            file.toFile().writeText(text = formatted, charset = Charsets.UTF_8)
        }
        return FormatResult(
            file = relativeName(file = file),
            modified = modified,
            correctedCount = findings.count { (_, corrected) -> corrected },
            remaining = findings.filterNot { (_, corrected) -> corrected }.map { (violation, _) -> violation },
        )
    }

    /**
     * `Code.fromPath` loses the file's trailing newline (false `final-newline` violations, trailing
     * whitespace missed), `Code.fromFile` keeps the content intact and still enables `.editorconfig` discovery.
     */
    private fun codeOf(file: Path): Code = Code.fromFile(file = file.toFile())

    private fun LintError.toViolation(file: Path): KtlintViolation =
        KtlintViolation(
            file = relativeName(file = file),
            line = line,
            column = col,
            ruleId = ruleId.value,
            detail = detail,
            autoCorrectable = canBeAutoCorrected,
        )

    private fun relativeName(file: Path): String = projectRoot.relativize(file).toString()
}
