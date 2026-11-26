package io.whozoss.agentos.plugins.ktlint

import com.pinterest.ktlint.rule.engine.api.Code
import com.pinterest.ktlint.rule.engine.api.KtLintRuleEngine
import com.pinterest.ktlint.rule.engine.api.LintError
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView

/**
 * Runs the embedded ktlint engine (standard ruleset) on single files.
 *
 * `.editorconfig` files are discovered by the engine from each file's location, so the target project's
 * own style configuration applies. Reported file names are relative to [projectRoot].
 *
 * One runner per project is a small object: it only keeps [projectRoot] to relativize the reported file names. The
 * engine behind it is the one of the whole plugin ([KtlintEngine]), shared by every project and thread: it is not tied
 * to a directory and keeps no state of a run, and it discovers the `.editorconfig` files from the location of each
 * file, so the files of two projects get the style of their own project.
 *
 * Known limit: the internal `.editorconfig` cache of ktlint grows with the number of distinct directories met, until
 * the plugin stops (see [KtlintEngine.reset]).
 *
 * @property projectRoot real path of the project root, used to relativize the reported file names.
 */
class KtlintRunner(
    private val projectRoot: Path,
) : StyleRunner {
    /** The shared engine, asked at each call so that a runner built before a stop of the plugin keeps working. */
    internal val engine: KtLintRuleEngine
        get() = KtlintEngine.shared

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
     * having truncated the file, leaving it empty. The new content is written to a temporary file next to the original,
     * then moved over it in one step, so that a full disk or a killed service never leaves a half-written file.
     */
    override fun format(file: Path): FormatResult {
        val original = file.toFile().readText(charset = Charsets.UTF_8)
        val findings = mutableListOf<Pair<KtlintViolation, Boolean>>()
        val formatted =
            engine.format(code = codeOf(file = file)) { lintError, corrected ->
                findings += lintError.toViolation(file = file) to corrected
            }
        val modified = formatted != original
        if (modified) writeAtomically(file = file, text = formatted)
        return FormatResult(
            file = relativeName(file = file),
            modified = modified,
            correctedCount = findings.count { (_, corrected) -> corrected },
            remaining = findings.filterNot { (_, corrected) -> corrected }.map { (violation, _) -> violation },
        )
    }

    /**
     * Replaces [file] by [text] without ever leaving it truncated: the text goes to a temporary file in the same
     * directory (same filesystem, so the move is a rename), whose name does not end in `.kt` / `.kts` so that it is
     * never taken for a source, then the temporary file is moved over [file]. The permissions of [file] are kept when
     * the filesystem has POSIX ones; the temporary file is deleted whatever happens.
     *
     * Only non-interruptible I/O is used on purpose (see [format]): [File.createTempFile] and [File.writeText] are
     * plain streams, [Files.move] is a rename, none of them goes through an interruptible channel (which would fail on
     * an interrupted thread, as [Files.writeString] does with a `ClosedByInterruptException`).
     */
    private fun writeAtomically(
        file: Path,
        text: String,
    ) {
        val temporary = File.createTempFile(TEMP_PREFIX, TEMP_SUFFIX, file.parent.toFile())
        try {
            temporary.writeText(text = text, charset = Charsets.UTF_8)
            copyPermissions(from = file, to = temporary.toPath())
            moveOver(source = temporary.toPath(), target = file)
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun copyPermissions(
        from: Path,
        to: Path,
    ) {
        Files.getFileAttributeView(from, PosixFileAttributeView::class.java)?.let { view ->
            Files.setPosixFilePermissions(to, view.readAttributes().permissions())
        }
    }

    /** Atomic move when the filesystem can, a plain replacing move otherwise. */
    private fun moveOver(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
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

    private companion object {
        const val TEMP_PREFIX = ".ktlint-"
        const val TEMP_SUFFIX = ".tmp"
    }
}
