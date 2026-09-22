package io.whozoss.agentos.exchange

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus
import java.nio.file.Path
import java.util.UUID

/**
 * Optional execution directory shared by a family of cases.
 *
 * [toolParameters] are values the workspace provider hands to the tool integration bound to this
 * workspace. They come from trusted server-side metadata, never from files inside the workspace.
 */
data class ExchangeWorkspace(
    val id: UUID,
    val workingDirectory: Path,
    val home: Path? = null,
    val toolParameters: Map<String, String> = emptyMap(),
)

/** Storage ownership and availability; backing resources and their lifecycle stay in the provider. */
data class ResolvedExchangeRoot(
    val path: Path,
    val ownerCaseId: UUID,
    val workspace: ExchangeWorkspace? = null,
    val unavailableReason: String? = null,
) {
    val isUsable: Boolean get() = unavailableReason == null

    /** Never fall back to another directory while the configured environment is unavailable. */
    fun requireUsable(): Path {
        unavailableReason?.let { throw ExchangeUnavailableException(it) }
        return path
    }

    fun requireWorkingDirectory(): Path {
        requireUsable()
        return workspace?.workingDirectory ?: path
    }
}

@ResponseStatus(HttpStatus.CONFLICT)
class ExchangeUnavailableException(message: String) : RuntimeException(message)
