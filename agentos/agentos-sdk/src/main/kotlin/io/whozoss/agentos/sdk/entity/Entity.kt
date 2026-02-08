package io.whozoss.agentos.sdk.entity

import java.util.UUID

/**
 * Marker interface for all entities managed by EntityService/EntityRepository.
 *
 * Entities must provide EntityMetadata for identity, audit trail, and soft delete.
 * The metadata is accessed via composition.
 */
interface Entity {
    val metadata: EntityMetadata

    /**
     * Convenience getter for the entity's unique identifier.
     * Delegates to metadata.id.
     */
    val id: UUID
        get() = metadata.id
}
