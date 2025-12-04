package io.biznet.agentos.orchestration

import java.util.UUID

/**
 * Model representing a case to be processed.
 */
data class CaseModel(
    val id: UUID,
    val projectId: UUID,
    val status: CaseStatus,
)
