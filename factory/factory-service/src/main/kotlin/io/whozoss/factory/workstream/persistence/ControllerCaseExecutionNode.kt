package io.whozoss.factory.workstream.persistence

import org.springframework.data.neo4j.core.schema.Id
import org.springframework.data.neo4j.core.schema.Node
import java.time.Instant

/**
 * Spring Data Neo4j projection of a controller case execution (Phase 9).
 *
 * The node [id] is the composite business key `(organizationId, workstreamId,
 * caseId)` encoded as a single string, so a scope-less access is impossible
 * by construction. New properties are defaulted so pre-existing nodes load
 * without any migration.
 *
 * At most one node per workstream carries `status = 'active'`; the invariant
 * is enforced by convention: only the `startFirst` / `archiveAndStart` write
 * paths of [Neo4jControllerCaseRepository] create or archive cases, always
 * inside a transaction. No database constraint is added (that would be a
 * migration).
 */
@Node("ControllerCaseExecution")
data class ControllerCaseExecutionNode(
    @Id
    val id: String,
    val organizationId: String,
    val workstreamId: String,
    val caseId: String,
    val controllerAgentRef: String,
    /** Lowercase wire vocabulary: `active` or `archived`. */
    val status: String,
    /** 1-based ordinal of this case within the workstream. */
    val sequence: Int,
    val startedAt: Instant,
    val archivedAt: Instant? = null,
    /** Why this case was archived / why the next case was created. */
    val compactionReason: String? = null,
    /** Bounded resumption context package (JSON string) captured at start. */
    val contextSummary: String? = null,
    /** The projection `workstreamRevision` ETag the package was built from. */
    val contextRevision: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
) {
    companion object {
        fun compositeId(organizationId: String, workstreamId: String, caseId: String): String =
            "$organizationId|$workstreamId|$caseId"
    }
}
