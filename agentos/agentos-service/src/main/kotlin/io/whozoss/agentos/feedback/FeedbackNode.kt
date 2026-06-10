package io.whozoss.agentos.feedback

import io.whozoss.agentos.caseEvent.CaseEventNode
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.feedback.Feedback
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import org.springframework.data.neo4j.core.schema.Relationship
import org.springframework.data.neo4j.core.schema.Relationship.Direction.OUTGOING
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Feedback].
 *
 * Stored as `(:Feedback)-[:FEEDBACK_ON]->(:CaseEvent)`.
 *
 * [caseEventId] is kept as a scalar property alongside the [caseEvent] @Relationship
 * field: queries filter on the scalar, while the graph edge is written on save and
 * is available for traversal. [toDomain] reads from the scalar.
 *
 * [caseEvent] is a nullable `var` so SDN can call the primary constructor before
 * injecting the @Relationship field via property injection.
 *
 * [caseId] and [namespaceId] are denormalized scalars to allow efficient
 * `findActiveByCaseId` queries without graph traversal.
 */
@Node("Feedback")
data class FeedbackNode(
    @Id
    val id: String,
    val namespaceId: String,
    val caseId: String,
    val caseEventId: String,
    val positive: Boolean,
    val type: String? = null,
    val comment: String? = null,
    val timestamp: Instant = Instant.now(),
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    @Relationship(type = "FEEDBACK_ON", direction = OUTGOING)
    var caseEvent: CaseEventNode? = null

    fun toDomain(): Feedback =
        Feedback(
            metadata =
                EntityMetadata(
                    id = UUID.fromString(id),
                    created = created,
                    createdBy = createdBy,
                    modified = modified,
                    modifiedBy = modifiedBy,
                    removed = removed ?: false,
                    version = version,
                ),
            namespaceId = UUID.fromString(namespaceId),
            caseId = UUID.fromString(caseId),
            caseEventId = UUID.fromString(caseEventId),
            positive = positive,
            type = type,
            comment = comment,
            timestamp = timestamp,
        )

    companion object {
        fun fromDomain(feedback: Feedback): FeedbackNode =
            FeedbackNode(
                id = feedback.id.toString(),
                namespaceId = feedback.namespaceId.toString(),
                caseId = feedback.caseId.toString(),
                caseEventId = feedback.caseEventId.toString(),
                positive = feedback.positive,
                type = feedback.type,
                comment = feedback.comment,
                timestamp = feedback.timestamp,
                version = feedback.metadata.version,
                created = feedback.metadata.created,
                createdBy = feedback.metadata.createdBy,
                modified = feedback.metadata.modified,
                modifiedBy = feedback.metadata.modifiedBy,
                removed = feedback.metadata.removed.takeIf { it },
            )
    }
}
