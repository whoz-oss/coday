package io.whozoss.agentos.git

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import java.nio.file.Path

/**
 * Where a case's files live, plus the workspace backing them when its family is equipped.
 *
 * [binding] is null for an ordinary family; [path] is then the historic per-case directory and is
 * always usable. When a binding is present the directory is the family's shared Exchange, with Git in repo/, and
 * whether it may be used depends on the workspace's preparation state.
 */
data class ResolvedExchangeRoot(
    val path: Path,
    val binding: CaseResourceBinding?,
) {
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
    fun requireUsable(): Path =
        when {
            isUsable -> path
            else -> throw CaseWorkspaceUnavailableException(unavailableMessage())
        }

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

/**
 * Raised when a case's workspace exists but is not usable (still preparing, failed, or removed).
 *
 * Distinct from "this family has no workspace", which is not an error: it is how every
 * non-equipped family works.
 *
 * Mapped to 409 rather than left to surface as a 500: for the common case — a workspace still
 * being prepared moments after the case was created — this is a normal, transient state that the
 * caller should retry, not a server fault. Without the mapping, opening a freshly created case
 * produced a stack trace in the logs and an opaque error in the UI.
 */
@ResponseStatus(HttpStatus.CONFLICT)
class CaseWorkspaceUnavailableException(
    message: String,
) : RuntimeException(message)
