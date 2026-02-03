package io.whozoss.agentos.caseFlow

import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.model.CaseStatus
import java.util.UUID

/**
 * Model representing a case to be processed.
 *
 * Implements Entity interface for standard CRUD operations.
 */
data class CaseModel(
    override val metadata: EntityMetadata = EntityMetadata(),
    val projectId: UUID,
    val status: CaseStatus,
) : Entity
