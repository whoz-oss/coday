package io.whozoss.agentos.workflow

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.persistence.OverlayKeyEncoding
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.util.toSlug
import org.springframework.data.annotation.CreatedBy
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.LastModifiedBy
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.Version
import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant
import java.util.UUID

/**
 * Spring Data Neo4j projection for [Workflow].
 *
 * [stepsJson] stores the ordered step list as a JSON array: steps are only ever read and
 * written as a whole, so a graph of step nodes would add traversal cost and no query value.
 *
 * [doubleKey] backs the `(namespaceId, name)` UNIQUE constraint (a composite constraint would
 * exempt platform rows, whose namespaceId is null). It is rewritten to a tombstone on
 * soft-delete so the name is immediately reusable.
 */
@Node("Workflow")
data class WorkflowNode(
    @Id val id: String,
    val namespaceId: String? = null,
    val name: String,
    val description: String? = null,
    val workflowVersion: Int = Workflow.CURRENT_VERSION,
    val stepsJson: String,
    val doubleKey: String,
    @Version val version: Long? = null,
    @CreatedDate val created: Instant = Instant.now(),
    @CreatedBy val createdBy: String? = null,
    @LastModifiedDate val modified: Instant = Instant.now(),
    @LastModifiedBy val modifiedBy: String? = null,
    val removed: Boolean? = null,
) {
    fun toDomain(): Workflow =
        Workflow(
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
            namespaceId = namespaceId?.let { UUID.fromString(it) },
            name = name,
            description = description,
            version = workflowVersion,
            steps = MAPPER.readValue(stepsJson, STEPS_TYPE),
        )

    companion object {
        private val MAPPER = jacksonObjectMapper()
        private val STEPS_TYPE = object : TypeReference<List<WorkflowStep>>() {}

        /** `(namespaceId, name)` discriminator; the name is slugged so "My Flow" and "my flow" collide. */
        fun computeDoubleKey(
            namespaceId: UUID?,
            name: String,
        ): String = (namespaceId?.toString() ?: OverlayKeyEncoding.NULL_ID_SENTINEL) + OverlayKeyEncoding.SEPARATOR + name.toSlug()

        fun tombstoneDoubleKey(id: String): String = OverlayKeyEncoding.tombstoneKey(id)

        fun fromDomain(workflow: Workflow): WorkflowNode =
            WorkflowNode(
                id = workflow.id.toString(),
                namespaceId = workflow.namespaceId?.toString(),
                name = workflow.name,
                description = workflow.description,
                workflowVersion = workflow.version,
                stepsJson = MAPPER.writeValueAsString(workflow.steps),
                doubleKey =
                    if (workflow.metadata.removed) {
                        tombstoneDoubleKey(workflow.id.toString())
                    } else {
                        computeDoubleKey(workflow.namespaceId, workflow.name)
                    },
                version = workflow.metadata.version,
                created = workflow.metadata.created,
                createdBy = workflow.metadata.createdBy,
                modified = workflow.metadata.modified,
                modifiedBy = workflow.metadata.modifiedBy,
                removed = workflow.metadata.removed.takeIf { it },
            )
    }
}
