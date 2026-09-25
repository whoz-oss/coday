package io.whozoss.agentos.git

import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitInvocation
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/** Reconcile one explicitly deleted family's missing checkout, never prune other worktrees. */
internal fun removeMissingWorktreeRegistration(
    runner: GitCommandRunner,
    gitDir: Path,
    rootCaseId: UUID,
    worktreePath: Path,
) {
    check(Files.notExists(worktreePath, NOFOLLOW_LINKS)) { "The worktree is not confirmed absent" }
    val common = gitDir.toRealPath()
    val registrations = common.resolve("worktrees")
    if (Files.notExists(registrations, NOFOLLOW_LINKS)) return
    check(Files.isDirectory(registrations, NOFOLLOW_LINKS)) { "The worktree registry is not a directory" }
    val admin = registrations.resolve(rootCaseId.toString())
    if (Files.notExists(admin, NOFOLLOW_LINKS)) return
    check(Files.isDirectory(admin, NOFOLLOW_LINKS)) { "The worktree registration is not a directory" }
    check(Files.notExists(admin.resolve("locked"), NOFOLLOW_LINKS)) { "The missing worktree is locked" }
    // Submodule object stores may contain commits not reachable from any parent-repository ref.
    check(Files.notExists(admin.resolve("modules"), NOFOLLOW_LINKS)) { "The missing worktree contains submodule metadata" }
    val pointer = admin.resolve("gitdir")
    check(Files.isRegularFile(pointer, NOFOLLOW_LINKS)) { "The worktree registration has no regular pointer" }
    val target = admin.resolve(Files.readString(pointer).trim()).normalize()
    check(target.fileName.toString() == ".git" && target.parent.fileName == worktreePath.fileName &&
        resolveMissingPath(target.parent) == resolveMissingPath(worktreePath.toAbsolutePath())) {
        "The worktree registration points to another Exchange"
    }
    val commonPointer = admin.resolve("commondir")
    check(Files.isRegularFile(commonPointer, NOFOLLOW_LINKS) &&
        admin.resolve(Files.readString(commonPointer).trim()).normalize().toRealPath() == common) {
        "The worktree registration points to another repository"
    }
    check(Files.isRegularFile(admin.resolve("HEAD"), NOFOLLOW_LINKS)) { "The missing worktree has no regular HEAD" }
    val head = runner.runOrThrow(GitInvocation(listOf("rev-parse", "--verify", "HEAD^{commit}"), gitDir = admin))
    check(head.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}"))) { "The missing worktree HEAD is not a commit id" }
    val index = admin.resolve("index")
    if (!Files.notExists(index, NOFOLLOW_LINKS)) {
        check(Files.isRegularFile(index, NOFOLLOW_LINKS)) { "The missing worktree has no regular index" }
        // Even without the checkout, its index may be the only copy of staged work.
        runner.runOrThrow(GitInvocation(listOf("diff-index", "--cached", "--quiet", head, "--"), gitDir = admin))
    }
    runner.runOrThrow(GitInvocation(
        listOf("update-ref", "--no-deref", "refs/agentos/retained/$rootCaseId", head), gitDir = common,
    ))
    // This directory belongs to the deleted root. Do not follow links or touch sibling entries.
    Files.walk(admin).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
}

/** Resolve existing ancestors so absent paths compare correctly through storage symlinks. */
private fun resolveMissingPath(path: Path): Path =
    if (Files.notExists(path, NOFOLLOW_LINKS)) {
        resolveMissingPath(requireNotNull(path.parent)).resolve(path.fileName)
    } else path.toRealPath()
