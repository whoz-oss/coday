package io.biznet.agentos.sdk.entity

/**
 * Marker interface for all entities managed by EntityService/EntityRepository.
 *
 * Entities must provide EntityMetadata for identity, audit trail, and soft delete.
 * The metadata is accessed via composition.
 */
interface Entity {
    val metadata: EntityMetadata
}
