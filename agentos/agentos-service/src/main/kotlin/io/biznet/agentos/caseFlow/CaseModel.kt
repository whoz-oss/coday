package io.biznet.agentos.caseFlow

import io.biznet.agentos.sdk.entity.Entity
import io.biznet.agentos.sdk.entity.EntityMetadata
import io.biznet.agentos.sdk.model.CaseStatus
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
) : Entity {
    /**
     * Convenience property for backward compatibility.
     */
    val id: UUID get() = metadata.id
}
