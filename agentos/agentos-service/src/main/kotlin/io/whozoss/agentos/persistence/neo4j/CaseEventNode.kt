package io.whozoss.agentos.persistence.neo4j

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Property
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [CaseEvent].
 *
 * [CaseEvent] is a sealed interface in the SDK with ~15 subtypes and Jackson
 * polymorphic serialisation. Rather than annotating each subtype with @Node
 * (which would require Spring Data Neo4j in the SDK module — an unacceptable
 * dependency), the full event is serialised to a JSON string stored in the
 * [payload] property. Only the metadata and routing fields are promoted to
 * first-class node properties for efficient Cypher queries.
 *
 * The [type] property mirrors [CaseEvent.type.value] (the Jackson discriminant)
 * and is indexed for fast retrieval by event type.
 *
 * Large payload concern (ADR-49): if [payload] grows beyond a reasonable size
 * (e.g. large tool responses or message content), callers should store the
 * content as a file and replace it with a file reference before saving.
 * That extraction is the responsibility of the repository implementation.
 */
@Node("CaseEvent")
data class CaseEventNode(
    @Id
    val id: String,
    val caseId: String,
    val namespaceId: String,
    val timestamp: Instant,
    /** Jackson discriminant value, e.g. "MessageEvent", "ToolRequestEvent". */
    val type: String,
    /** Full JSON serialisation of the [CaseEvent] subtype. */
    @Property("payload")
    val payload: String,
    // EntityMetadata fields
    val created: Instant = Instant.now(),
    val createdBy: String? = null,
    val modified: Instant = Instant.now(),
    val modifiedBy: String? = null,
    @Property("removed")
    val removed: Boolean = false,
) {
    fun toDomain(objectMapper: ObjectMapper): CaseEvent =
        objectMapper.readValue(payload, CaseEvent::class.java)

    companion object {
        fun fromDomain(
            event: CaseEvent,
            objectMapper: ObjectMapper,
        ): CaseEventNode =
            CaseEventNode(
                id = event.id.toString(),
                caseId = event.caseId.toString(),
                namespaceId = event.namespaceId.toString(),
                timestamp = event.timestamp,
                type = event.type.value,
                payload = objectMapper.writeValueAsString(event),
                created = event.metadata.created,
                createdBy = event.metadata.createdBy,
                modified = event.metadata.modified,
                modifiedBy = event.metadata.modifiedBy,
                removed = event.metadata.removed,
            )
    }
}
