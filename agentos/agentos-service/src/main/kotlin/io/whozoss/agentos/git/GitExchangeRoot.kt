package io.whozoss.agentos.git

import io.whozoss.agentos.exchange.ExchangeWorkspace
import io.whozoss.agentos.exchange.ResolvedExchangeRoot
import java.nio.file.Path
import java.util.UUID

/**
 * Where a case's files live, plus the workspace backing them when its family is equipped.
 *
 * [binding] is null for an ordinary family; [path] is then the historic per-case directory and is
 * always usable. When a binding is present the directory is the family's shared Exchange, with Git in repo/, and
 * whether it may be used depends on the workspace's preparation state.
 */
data class GitExchangeRoot(
    val path: Path,
    val binding: CaseResourceBinding?,
    val ownerCaseId: UUID,
    /** HOME shared by the family's setup and shell tools; null for an ordinary family. */
    val supportDirectory: Path? = null,
    /** Git context handed to the `GIT` tool integration; empty for an ordinary family. */
    val toolParameters: Map<String, String> = emptyMap(),
) {
    val exchange: ResolvedExchangeRoot get() = ResolvedExchangeRoot(
        path = path,
        ownerCaseId = ownerCaseId,
        workspace = binding?.let { ExchangeWorkspace(it.rootCaseId, repositoryPath, supportDirectory, toolParameters) },
        unavailableReason = if (isUsable) null else unavailableMessage(),
    )

    val repositoryPath: Path get() = if (binding == null) path else path.resolve("repo")

    fun requireRepository(): Path {
        requireUsable()
        return repositoryPath
    }

    /** Whether files may be read or written here right now. */
    val isUsable: Boolean get() = binding == null || binding.status.isUsable

    /** Whether a caller should wait rather than fail: the workspace is still being prepared. */
    val isPending: Boolean get() = binding != null && binding.status.isPending

    /**
     * The path, or a failure explaining why the workspace is unavailable.
     *
     * Callers must not fall back to another directory: writing a case's work into the namespace
     * checkout, or into a per-case directory the family does not use, silently splits the family's
     * files across two locations.
     */
    fun requireUsable(): Path = exchange.requireUsable()

    /**
     * Why the workspace cannot be used, in terms the person who hit it can act on.
     *
     * This reaches the UI verbatim (`server.error.include-message: always`), and it shares its 409
     * with a plain upload name collision, so it has to say which of the two happened. It also has to
     * separate "wait" from "this will not fix itself": a case whose workspace is still being
     * prepared is a few seconds from working, a failed one needs someone to look at it.
     */
    private fun unavailableMessage(): String =
        when (binding?.status) {
            CaseResourceStatus.REQUESTED, CaseResourceStatus.PREPARING ->
                "The workspace for this case is still being prepared. It will be ready in a moment."
            CaseResourceStatus.FAILED ->
                "The workspace for this case could not be prepared" +
                    (binding.failureReason?.let { ": $it" } ?: ".")
            CaseResourceStatus.DELETING, CaseResourceStatus.REMOVED ->
                "The workspace for this case has been removed."
            null -> "This case has no usable workspace."
            else -> "The workspace for this case is ${binding.status} and cannot be used."
        }
}
