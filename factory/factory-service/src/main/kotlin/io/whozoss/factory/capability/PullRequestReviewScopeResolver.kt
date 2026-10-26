package io.whozoss.factory.capability

import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.environment.port.GitCommandRunner
import io.whozoss.factory.persistence.TenantScope
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.stereotype.Component

data class PullRequestReviewScope(
    val createdFiles: List<String>,
    val modifiedFiles: List<String>,
    val changedLines: Map<String, List<IntRange>>,
)

sealed interface PullRequestReviewScopeResult {
    data class Resolved(val scope: PullRequestReviewScope) : PullRequestReviewScopeResult
    data class Unavailable(val reason: String) : PullRequestReviewScopeResult
}

/** Computes an authoritative PR-wide file scope from Factory's frozen workflow base. */
@Component
class PullRequestReviewScopeResolver(
    private val environments: WorkEnvironmentRepository,
    private val git: GitCommandRunner,
) {
    fun resolve(scope: TenantScope, workflowId: String): PullRequestReviewScopeResult {
        val environment = environments.findLatestByWorkflowId(scope, workflowId)
            ?: return unavailable("No Factory work environment is bound to this workflow.")
        val baseCommit = environment.baseCommit?.takeIf(SHA::matches)
            ?: return unavailable("The workflow has no valid frozen base commit.")
        val root = runCatching { Path.of(environment.worktreePath).toAbsolutePath().normalize().toRealPath() }
            .getOrElse { return unavailable("The workflow worktree is unavailable.") }
        if (!Files.isDirectory(root) || !Files.exists(root.resolve(".git"))) {
            return unavailable("The workflow worktree is not a Git worktree.")
        }
        val base = git.run(root, listOf("cat-file", "-e", "$baseCommit^{commit}"))
        if (base.exitCode != 0) return unavailable("The frozen base commit is unavailable in the workflow worktree.")

        val status = git.run(root, listOf("diff", "--name-status", "-z", "--find-renames", baseCommit, "--"))
        if (status.exitCode != 0) return unavailable("Factory could not calculate the current PR file scope.")
        val parsed = parseNameStatus(status.stdout)
            ?: return unavailable("Git returned a malformed current PR file scope.")
        val untracked = git.run(root, listOf("ls-files", "--others", "--exclude-standard", "-z", "--"))
        if (untracked.exitCode != 0) return unavailable("Factory could not inspect untracked PR files.")
        val untrackedPaths = parseNulPaths(untracked.stdout)
            ?: return unavailable("Git returned malformed untracked PR files.")

        val created = (parsed.created + untrackedPaths).distinct().sorted()
        val modified = parsed.modified.distinct().filterNot(created::contains).sorted()
        val changedLines = LinkedHashMap<String, List<IntRange>>()
        for (path in modified) {
            val diff = git.run(root, listOf("diff", "--unified=0", "--no-ext-diff", baseCommit, "--", path))
            if (diff.exitCode != 0) return unavailable("Factory could not calculate changed lines for '$path'.")
            val ranges = parseChangedLines(diff.stdout)
                ?: return unavailable("Git returned malformed changed lines for '$path'.")
            if (ranges.isEmpty()) return unavailable("Factory could not establish changed lines for '$path'.")
            changedLines[path] = ranges
        }
        return PullRequestReviewScopeResult.Resolved(PullRequestReviewScope(created, modified, changedLines))
    }

    private data class ParsedPaths(val created: List<String>, val modified: List<String>)

    private fun parseNameStatus(raw: String): ParsedPaths? {
        val fields = raw.split('\u0000').filter(String::isNotEmpty)
        val created = mutableListOf<String>()
        val modified = mutableListOf<String>()
        var index = 0
        while (index < fields.size) {
            val status = fields[index++]
            when (status.firstOrNull()) {
                'A' -> (fields.getOrNull(index++) ?: return null).also {
                    if (!safeRelativePath(it)) return null
                    created += it
                }
                'M', 'T' -> (fields.getOrNull(index++) ?: return null).also {
                    if (!safeRelativePath(it)) return null
                    modified += it
                }
                'R' -> {
                    val oldPath = fields.getOrNull(index++) ?: return null
                    val newPath = fields.getOrNull(index++) ?: return null
                    if (!safeRelativePath(oldPath) || !safeRelativePath(newPath)) return null
                    modified += newPath
                }
                'D' -> {
                    val deleted = fields.getOrNull(index++) ?: return null
                    if (!safeRelativePath(deleted)) return null
                }
                else -> return null
            }
        }
        return ParsedPaths(created, modified)
    }

    private fun parseNulPaths(raw: String): List<String>? = raw.split('\u0000')
        .filter(String::isNotEmpty)
        .takeIf { paths -> paths.all(::safeRelativePath) }

    private fun safeRelativePath(value: String): Boolean {
        val path = runCatching { Path.of(value) }.getOrNull() ?: return false
        return value.isNotBlank() && !path.isAbsolute && path.normalize().none { it.toString() == ".." }
    }

    private fun parseChangedLines(diff: String): List<IntRange>? {
        val ranges = mutableListOf<IntRange>()
        for (line in diff.lineSequence()) {
            if (!line.startsWith("@@")) continue
            val match = HUNK.find(line) ?: return null
            val start = match.groupValues[1].toIntOrNull() ?: return null
            val count = match.groupValues[2].takeIf(String::isNotEmpty)?.toIntOrNull() ?: 1
            if (count > 0) ranges += start..(start + count - 1)
        }
        return ranges
    }

    private fun unavailable(reason: String) = PullRequestReviewScopeResult.Unavailable(reason)

    private companion object {
        val SHA = Regex("^[0-9a-fA-F]{40}$")
        val HUNK = Regex("""@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@""")
    }
}
