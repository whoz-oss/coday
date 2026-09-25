package io.whozoss.agentos.git

import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConflictException
import io.whozoss.agentos.git.core.GitCommandResult
import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitInvocation
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

data class ExchangeGitTarget(val path: Path, val gitDir: Path, val commonDir: Path, val mainBranch: String, val fallbackBase: String? = null)
enum class ExchangeGitFileStatus { ADDED, MODIFIED, DELETED, TYPE_CHANGED, CONFLICTED, UNTRACKED }
data class ExchangeDiffFile(
    val path: String,
    val additions: Int?,
    val deletions: Int?,
    val untracked: Boolean = false,
    val status: ExchangeGitFileStatus = ExchangeGitFileStatus.MODIFIED,
)
data class ExchangeGitChanges(val base: String, val files: List<ExchangeDiffFile>) {
    val additions: Int get() = files.sumOf { it.additions ?: 0 }
    val deletions: Int get() = files.sumOf { it.deletions ?: 0 }
}
data class ExchangeFileDiff(val patch: String = "", val message: String? = null)

/** Read-only, local Git observations. Never invokes a forge, external diff or text conversion. */
@Service
class ExchangeGitDiff(private val runner: GitCommandRunner) {
    fun branch(target: ExchangeGitTarget): String? {
        runner.assertNoHostileLocalConfig(target.commonDir)
        val result = run(target, listOf("symbolic-ref", "--quiet", "--short", "HEAD"))
        return when (result.exitCode) {
            0 -> result.stdout.trim()
            1 -> null
            else -> throw ConflictException("Cannot read the repository branch")
        }
    }

    fun changes(target: ExchangeGitTarget): ExchangeGitChanges {
        runner.assertNoHostileLocalConfig(target.commonDir)
        val base = base(target)
        val files = trackedChanges(target, base).toMutableList()
        val untracked = checked(run(target, listOf("ls-files", "--others", "--exclude-standard", "-z")))
            .split('\u0000').filter { it.isNotEmpty() }.toSet()
        if ((untracked + files.map { it.path }).size > 5000) throw ConflictException("Too many changed files to display (limit: 5000)")
        val recreated = files.filter { it.status == ExchangeGitFileStatus.DELETED && it.path in untracked }.map { it.path }.toSet()
        if (recreated.isNotEmpty()) {
            recreated.forEach { safeFile(target.path, it) }
            val replacements = withBaseIndex(target, base) { inspection ->
                trackedChanges(inspection, base, recreated.map { ":(literal)$it" })
            }
            files.removeAll { it.path in recreated }
            files += replacements.map { file ->
                when {
                    Files.isSymbolicLink(safeFile(target.path, file.path)) -> file.copy(additions = null, deletions = null, untracked = true)
                    else -> file.copy(untracked = true)
                }
            }
        }
        for (relative in untracked - recreated) {
            val path = safeFile(target.path, relative)
            // Do not follow untracked links out of the Exchange. Git tracks the link, not its target.
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                files += ExchangeDiffFile(relative, null, null, true, ExchangeGitFileStatus.UNTRACKED)
                continue
            }
            val row = checked(run(target, diffArgs + listOf("--no-index", "--numstat", "--", "/dev/null", relative)), setOf(0, 1))
            val parts = row.split('\t', limit = 3)
            files += ExchangeDiffFile(relative, parts[0].trim().toIntOrNull(), parts.getOrNull(1)?.toIntOrNull(), true, ExchangeGitFileStatus.UNTRACKED)
        }
        return ExchangeGitChanges(base, files.sortedBy { it.path })
    }

    private fun trackedChanges(target: ExchangeGitTarget, base: String, paths: List<String> = emptyList()): List<ExchangeDiffFile> {
        // Numstat alone cannot distinguish an added file from an existing file gaining lines,
        // or an emptied file from a deleted one. Read Git's actual change kind, NUL-delimited.
        val names = checked(run(target, diffArgs + listOf("--name-status", "-z", base, "--") + paths))
            .split('\u0000').dropLast(1)
        check(names.size % 2 == 0) { "Incomplete Git change list" }
        val statuses = names.chunked(2).associate { (code, path) ->
            path to when (code) {
                "A" -> ExchangeGitFileStatus.ADDED
                "D" -> ExchangeGitFileStatus.DELETED
                "T" -> ExchangeGitFileStatus.TYPE_CHANGED
                "U" -> ExchangeGitFileStatus.CONFLICTED
                "M" -> ExchangeGitFileStatus.MODIFIED
                else -> throw ConflictException("Unsupported Git change kind")
            }
        }
        val conflicted = checked(run(target, diffArgs + listOf("--name-only", "--diff-filter=U", "-z", "--") + paths))
            .split('\u0000').filter { it.isNotEmpty() }.toSet()
        val tracked = checked(run(target, diffArgs + listOf("--numstat", "-z", base, "--") + paths))
        return tracked.split('\u0000').filter { it.isNotEmpty() }.map { row ->
            val parts = row.split('\t', limit = 3)
            check(parts.size == 3)
            val status = if (parts[2] in conflicted) ExchangeGitFileStatus.CONFLICTED else statuses[parts[2]]
                ?: throw ConflictException("The repository changed during inspection; retry shortly")
            ExchangeDiffFile(parts[2], parts[0].toIntOrNull(), parts[1].toIntOrNull(), status = status)
        }
    }

    fun file(target: ExchangeGitTarget, relative: String): ExchangeFileDiff {
        safeFile(target.path, relative)
        val changes = changes(target)
        val file = changes.files.find { it.path == relative } ?: throw BadRequestException("This file is not in the current diff")
        if (file.additions == null) return ExchangeFileDiff(message = "Binary file or symbolic link — no text preview")
        val isNew = file.status == ExchangeGitFileStatus.UNTRACKED
        val args = if (isNew) diffArgs + listOf("--no-index", "--", "/dev/null", relative)
            else diffArgs + listOf(changes.base, "--", ":(literal)$relative")
        val result = when {
            file.untracked && !isNew -> withBaseIndex(target, changes.base) { run(it, args) }
            else -> run(target, args)
        }
        if (result.truncated) return ExchangeFileDiff(message = "This file's diff is too large to preview. Open it from the file tree.")
        val patch = checked(result, if (isNew) setOf(0, 1) else setOf(0))
        return ExchangeFileDiff(patch, if (patch.isEmpty()) "No textual changes (empty file or file mode change)" else null)
    }

    private fun <T> withBaseIndex(target: ExchangeGitTarget, base: String, inspect: (ExchangeGitTarget) -> T): T {
        // A staged deletion hides a recreated file from ordinary git diff. Give Git a private
        // base index for these paths, sharing the object store without touching the live index.
        val directory = Files.createTempDirectory("agentos-diff-index-")
        return try {
            Files.writeString(directory.resolve("commondir"), "${target.commonDir.toAbsolutePath().normalize()}\n")
            Files.writeString(directory.resolve("HEAD"), "$base\n")
            val inspection = target.copy(gitDir = directory)
            checked(run(inspection, listOf("read-tree", base)))
            inspect(inspection)
        } finally {
            Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun base(target: ExchangeGitTarget): String {
        val main = run(target, listOf("rev-parse", "--verify", "refs/remotes/origin/${target.mainBranch}^{commit}"))
        var reference = if (main.successful) main.stdout.trim() else target.fallbackBase
        // Provisioning fetches a frozen case base independently of origin/main. The latter
        // may still reflect the initial clone and must not count upstream commits as case work.
        val frozen = target.fallbackBase
        if (reference != null && frozen != null && frozen.matches(Regex("[a-fA-F0-9]{40,64}")) &&
            run(target, listOf("merge-base", "--is-ancestor", reference, frozen)).successful) {
            reference = frozen
        }
        if (reference != null) {
            require(reference.matches(Regex("[a-fA-F0-9]{40,64}")))
            val mergeBase = run(target, listOf("merge-base", reference, "HEAD"))
            if (mergeBase.successful) return checked(mergeBase).trim()
            throw ConflictException("The branch has no common ancestor with the main branch")
        }
        throw ConflictException("The main branch is unavailable locally; fetch it before opening the diff")
    }

    private fun safeFile(root: Path, relative: String): Path {
        val path = Path.of(relative)
        if (path.isAbsolute || relative.isBlank() || path.any { it.toString() in setOf("..", ".git") }) {
            throw BadRequestException("Invalid diff path")
        }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(path).normalize()
        if (!resolved.startsWith(normalizedRoot)) throw BadRequestException("Invalid diff path")
        var parent = resolved.parent
        while (parent != null && parent != normalizedRoot) {
            if (Files.isSymbolicLink(parent)) throw BadRequestException("Cannot preview files through a symbolic link")
            parent = parent.parent
        }
        return resolved
    }

    private fun run(target: ExchangeGitTarget, args: List<String>): GitCommandResult.Completed =
        runner.run(GitInvocation(args, gitDir = target.gitDir, workTree = target.path, workingDirectory = target.path, timeout = Duration.ofSeconds(20)))
            as? GitCommandResult.Completed ?: throw ConflictException("Git inspection did not complete; retry shortly")

    private fun checked(result: GitCommandResult.Completed, codes: Set<Int> = setOf(0)): String {
        if (result.truncated) throw ConflictException("The change list is too large to display")
        if (result.exitCode !in codes) throw ConflictException("Git inspection failed; the repository may be changing. Retry shortly.")
        return result.stdout
    }

    private val diffArgs = listOf("diff", "--no-ext-diff", "--no-textconv", "--no-renames", "--no-color", "--ignore-submodules=all")
}
