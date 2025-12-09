package io.biznet.agentos.orchestration

import java.time.Instant
import java.util.UUID

/**
 * Marker interface for all entities managed by EntityService/EntityRepository.
 *
 * Entities must provide EntityMetadata for identity, audit trail, and soft delete.
 * The metadata is accessed via composition.
 */
sealed interface Entity {
    val metadata: EntityMetadata
}

/**
 * Standard metadata for all entities in the system.
 *
 * Encapsulates common fields:
 * - Identity (id)
 * - Audit trail (created, createdBy, modified, modifiedBy)
 * - Soft delete (removed)
 *
 * This class will be annotated with Spring Data annotations when we add database persistence.
 * Spring Data will handle automatic timestamp updates on save.
 */
data class EntityMetadata(
    val id: UUID = UUID.randomUUID(),
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    val removed: Boolean = false,
) {
    fun markAsRemoved(): EntityMetadata = copy(removed = true)
}
