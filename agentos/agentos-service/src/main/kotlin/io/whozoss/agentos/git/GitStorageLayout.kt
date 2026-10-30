package io.whozoss.agentos.git

import io.whozoss.agentos.exchange.ExchangeStorageService
import io.whozoss.agentos.git.core.GitLayout
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/**
 * Internal bare repository of the namespace, beside its Exchange roots and never under them, so the
 * file APIs never expose it. The Exchange only knows its own roots; the Git layout belongs here.
 */
fun ExchangeStorageService.namespaceGitDirectory(namespaceId: UUID): Path =
    namespaceDirectory(namespaceId).resolve("repository.git")

/** Workspace-owned setup state, outside both browsable Exchange roots and the Git checkout. */
fun ExchangeStorageService.workspaceSupportDirectory(
    namespaceId: UUID,
    rootCaseId: UUID,
): Path = namespaceDirectory(namespaceId).resolve("workspace-support").resolve(rootCaseId.toString())

/** Git's administrative directory for the worktree of [rootCaseId], in this namespace repository. */
fun Path.worktreeRegistration(rootCaseId: UUID): Path = resolve(GitLayout.WORKTREES_DIR).resolve(rootCaseId.toString())

/** Delete [path] and its content. Symbolic links are deleted themselves, never followed to their targets. */
internal fun deleteTreeWithoutFollowingLinks(path: Path) {
    if (Files.exists(path, NOFOLLOW_LINKS)) {
        Files.walk(path).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}

/** The namespace's directory, parent of its shared Exchange root. */
private fun ExchangeStorageService.namespaceDirectory(namespaceId: UUID): Path = namespaceRoot(namespaceId).parent
