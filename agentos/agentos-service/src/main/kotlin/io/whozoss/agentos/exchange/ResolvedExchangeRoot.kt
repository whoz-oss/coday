package io.whozoss.agentos.exchange

import io.whozoss.agentos.exception.ConflictException
import java.nio.file.Path
import java.util.UUID

/** Storage ownership and availability; backing resources and their lifecycle stay in the provider. */
data class ResolvedExchangeRoot(
    val path: Path,
    val ownerCaseId: UUID,
    val unavailableReason: String? = null,
) {
    /** Never fall back to another directory while the configured environment is unavailable. */
    fun requireUsable(): Path {
        unavailableReason?.let { throw ConflictException(it) }
        return path
    }
}
