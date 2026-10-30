package io.whozoss.agentos.configAgent

import java.util.UUID

/**
 * Where one planned operation stands against the real state, as one user sees it.
 *
 * [entityId] is the entity the operation targets or, for a CREATE, the existing entity it matched.
 */
data class OperationEvaluation(
    val status: ConfigOperationStatus,
    val entityId: UUID? = null,
    val entity: ConfigEntity? = null,
    val reason: String? = null,
)
