package io.whozoss.agentos.sdk.entity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.time.Instant
import java.util.UUID

/**
 * Standard metadata for all entities in the system.
 *
 * Encapsulates common fields:
 * - Identity (id)
 * - Audit trail (created, createdBy, modified, modifiedBy)
 * - Soft delete (removed)
 * - Optimistic locking version (version)
 *
 * This class will be annotated with Spring Data annotations when we add database persistence.
 * Spring Data will handle automatic timestamp updates on save.
 *
 * [version] is used by Spring Data Neo4j for optimistic locking and for the `isNew()` heuristic:
 * a null version means the entity has never been persisted, so Spring Data treats it as new
 * (triggering `@CreatedBy`). A non-null version means the entity already exists (update path).
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) allows forward-compatible deserialization
 * when new fields are added to the metadata in future versions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class EntityMetadata(
    val id: UUID = UUID.randomUUID(),
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    var removed: Boolean = false,
    val version: Long? = null,
) {
    fun markAsRemoved(): EntityMetadata = copy(removed = true)
}
