package io.whozoss.factory.workflow.persistence

import org.springframework.data.neo4j.repository.Neo4jRepository
import org.springframework.data.neo4j.repository.query.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * Spring Data Neo4j repositories backing the workflow aggregate.
 *
 * Besides the CRUD inherited from [Neo4jRepository], each interface declares the
 * scope-constrained lookups and the graph-native compare-and-swap statements
 * that replace the former `SELECT ... WHERE organization_id = ...` /
 * `UPDATE ... WHERE revision = :expectedRevision` SQL. Every query is scoped by
 * the composite tenant key.
 */
interface SpringDataNeo4jWorkflowDefinitionRepository : Neo4jRepository<WorkflowDefinitionNode, String> {

    /** Every definition of the organization, ordered by `(workflowType, version)`. */
    @Query(
        """
        MATCH (d:WorkflowDefinition)
        WHERE d.organizationId = ${'$'}organizationId
        RETURN d
        ORDER BY d.workflowType ASC, d.version ASC
        """,
    )
    fun findAllByOrganization(@Param("organizationId") organizationId: String): List<WorkflowDefinitionNode>
}

interface SpringDataNeo4jWorkflowInstanceRepository : Neo4jRepository<WorkflowInstanceNode, String> {

    /** Active instances of a namespace, ordered by `workflowId`. */
    @Query(
        """
        MATCH (i:WorkflowInstance)
        WHERE i.organizationId = ${'$'}organizationId
          AND i.workstreamId = ${'$'}workstreamId
          AND i.namespaceId = ${'$'}namespaceId
          AND i.status = ${'$'}status
        RETURN i
        ORDER BY i.workflowId ASC
        """,
    )
    fun findAllByScopeAndNamespaceAndStatus(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String,
        @Param("status") status: String,
    ): List<WorkflowInstanceNode>

    /**
     * Compare-and-swaps the whole instance snapshot at [expectedRevision]; only a
     * still-`active` instance matches. Returns the number of nodes updated
     * (0 or 1).
     */
    @Query(
        """
        MATCH (i:WorkflowInstance {id: ${'$'}id})
        WHERE i.revision = ${'$'}expectedRevision AND i.status = 'active'
        SET i.revision = ${'$'}revision,
            i.status = ${'$'}status,
            i.instance = ${'$'}instance,
            i.projection = ${'$'}projection,
            i.updatedAt = ${'$'}updatedAt
        RETURN count(i) AS updated
        """,
    )
    fun casUpdateInstance(
        @Param("id") id: String,
        @Param("expectedRevision") expectedRevision: Int,
        @Param("revision") revision: Int,
        @Param("status") status: String,
        @Param("instance") instance: String,
        @Param("projection") projection: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Long

    /** Flips the instance status when it currently matches [fromStatus]. */
    @Query(
        """
        MATCH (i:WorkflowInstance {id: ${'$'}id})
        WHERE i.status = ${'$'}fromStatus
        SET i.status = ${'$'}toStatus, i.updatedAt = ${'$'}updatedAt
        RETURN count(i) AS updated
        """,
    )
    fun casUpdateInstanceStatus(
        @Param("id") id: String,
        @Param("fromStatus") fromStatus: String,
        @Param("toStatus") toStatus: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Long

    /**
     * Atomically reserves the run's root case (Lot B durable case family) on an
     * `active` instance that has no root case yet. The `WHERE i.rootCaseId IS
     * NULL` predicate is the compare-and-set: of N concurrent reservations for
     * the same instance exactly ONE matches and writes (count 1); the losers
     * match nothing (count 0) and must re-read the winning value. An already
     * reserved instance is therefore never overwritten.
     *
     * The revision is incremented so the reservation is an observable instance
     * mutation (existing `casUpdateInstance` statements keep the root case
     * untouched).
     */
    @Query(
        """
        MATCH (i:WorkflowInstance {id: ${'$'}id})
        WHERE i.status = 'active' AND i.rootCaseId IS NULL
        SET i.rootCaseId = ${'$'}rootCaseId,
            i.revision = i.revision + 1,
            i.updatedAt = ${'$'}updatedAt
        RETURN count(i) AS updated
        """,
    )
    fun reserveRootCase(
        @Param("id") id: String,
        @Param("rootCaseId") rootCaseId: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Long

    /**
     * Atomically increments the run's plan-amendment counter (Lot E) and returns
     * the new value. `coalesce` keeps legacy nodes (without the field) at `0`.
     * A missing instance returns no row, so the caller observes `0`.
     */
    @Query(
        """
        MATCH (i:WorkflowInstance {id: ${'$'}id})
        SET i.amendmentSeq = coalesce(i.amendmentSeq, 0) + 1,
            i.updatedAt = ${'$'}updatedAt
        RETURN i.amendmentSeq AS amendmentSeq
        """,
    )
    fun incrementAmendmentSeq(
        @Param("id") id: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Long?
}

interface SpringDataNeo4jWorkflowProjectionRepository : Neo4jRepository<WorkflowProjectionNode, String> {

    /**
     * Projections of a lifecycle state, optionally filtered by namespace.
     *
     * A `null` [namespaceId] matches every namespace of the scope (the Cypher
     * `IS NULL OR` guard); the tenant key is always applied.
     */
    @Query(
        """
        MATCH (p:WorkflowProjection)
        WHERE p.organizationId = ${'$'}organizationId
          AND p.workstreamId = ${'$'}workstreamId
          AND p.lifecycleState = ${'$'}lifecycleState
          AND (${'$'}namespaceId IS NULL OR p.namespaceId = ${'$'}namespaceId)
        RETURN p
        ORDER BY p.workflowId ASC
        """,
    )
    fun findAllByScopeAndLifecycle(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String?,
        @Param("lifecycleState") lifecycleState: String,
    ): List<WorkflowProjectionNode>

    /** Transitions the lifecycle state when it currently is one of [fromStates]. */
    @Query(
        """
        MATCH (p:WorkflowProjection {id: ${'$'}id})
        WHERE p.lifecycleState IN ${'$'}fromStates
        SET p.lifecycleState = ${'$'}toState, p.updatedAt = ${'$'}updatedAt
        RETURN count(p) AS updated
        """,
    )
    fun casUpdateLifecycle(
        @Param("id") id: String,
        @Param("fromStates") fromStates: List<String>,
        @Param("toState") toState: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Long
}

interface SpringDataNeo4jWorkflowStepStateRepository : Neo4jRepository<WorkflowStepStateNode, String> {

    /** Every step state of a workflow instance, in insertion order. */
    @Query(
        """
        MATCH (s:WorkflowStepState)
        WHERE s.organizationId = ${'$'}organizationId
          AND s.workstreamId = ${'$'}workstreamId
          AND s.namespaceId = ${'$'}namespaceId
          AND s.workflowId = ${'$'}workflowId
        RETURN s
        ORDER BY s.createdAt ASC, s.stepId ASC
        """,
    )
    fun findAllByInstance(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String,
        @Param("workflowId") workflowId: String,
    ): List<WorkflowStepStateNode>

    /** Compare-and-swaps a step status at [expectedRevision], incrementing it. */
    @Query(
        """
        MATCH (s:WorkflowStepState {id: ${'$'}id})
        WHERE s.revision = ${'$'}expectedRevision
        SET s.status = ${'$'}status,
            s.payload = ${'$'}payload,
            s.revision = s.revision + 1,
            s.updatedAt = ${'$'}updatedAt
        RETURN count(s) AS updated
        """,
    )
    fun casUpdateStatus(
        @Param("id") id: String,
        @Param("expectedRevision") expectedRevision: Int,
        @Param("status") status: String,
        @Param("payload") payload: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Long

    /**
     * Atomically claims a step (status in [fromStatuses] -> [status]), incrementing
     * its revision and replacing its payload in the same statement. Returns the
     * number of nodes updated (0 or 1).
     */
    @Query(
        """
        MATCH (s:WorkflowStepState {id: ${'$'}id})
        WHERE s.status IN ${'$'}fromStatuses
        SET s.status = ${'$'}status,
            s.payload = ${'$'}payload,
            s.revision = s.revision + 1,
            s.updatedAt = ${'$'}updatedAt
        RETURN count(s) AS updated
        """,
    )
    fun casClaimStep(
        @Param("id") id: String,
        @Param("fromStatuses") fromStatuses: List<String>,
        @Param("status") status: String,
        @Param("payload") payload: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Long
}

interface SpringDataNeo4jWorkflowTransitionRepository : Neo4jRepository<WorkflowTransitionNode, String> {

    /** The append-only transition log of an instance, oldest first. */
    @Query(
        """
        MATCH (t:WorkflowTransition)
        WHERE t.organizationId = ${'$'}organizationId
          AND t.workstreamId = ${'$'}workstreamId
          AND t.namespaceId = ${'$'}namespaceId
          AND t.workflowId = ${'$'}workflowId
        RETURN t
        ORDER BY t.createdAt ASC
        """,
    )
    fun findAllByInstance(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String,
        @Param("workflowId") workflowId: String,
    ): List<WorkflowTransitionNode>
}

interface SpringDataNeo4jWorkflowCodeTransitionRepository : Neo4jRepository<WorkflowCodeTransitionNode, String> {

    /** The append-only code-transition log of an instance, oldest first. */
    @Query(
        """
        MATCH (t:WorkflowCodeTransition)
        WHERE t.organizationId = ${'$'}organizationId
          AND t.workstreamId = ${'$'}workstreamId
          AND t.namespaceId = ${'$'}namespaceId
          AND t.workflowId = ${'$'}workflowId
        RETURN t
        ORDER BY t.createdAt ASC
        """,
    )
    fun findAllByInstance(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String,
        @Param("workflowId") workflowId: String,
    ): List<WorkflowCodeTransitionNode>
}

interface SpringDataNeo4jWorkflowEvidenceRepository : Neo4jRepository<WorkflowEvidenceNode, String> {

    /** The append-only evidence log of an instance, oldest first. */
    @Query(
        """
        MATCH (e:WorkflowEvidence)
        WHERE e.organizationId = ${'$'}organizationId
          AND e.workstreamId = ${'$'}workstreamId
          AND e.namespaceId = ${'$'}namespaceId
          AND e.workflowId = ${'$'}workflowId
        RETURN e
        ORDER BY e.createdAt ASC
        """,
    )
    fun findAllByInstance(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String,
        @Param("workflowId") workflowId: String,
    ): List<WorkflowEvidenceNode>

    /** The evidence of one step, oldest first. */
    @Query(
        """
        MATCH (e:WorkflowEvidence)
        WHERE e.organizationId = ${'$'}organizationId
          AND e.workstreamId = ${'$'}workstreamId
          AND e.namespaceId = ${'$'}namespaceId
          AND e.workflowId = ${'$'}workflowId
          AND e.stepId = ${'$'}stepId
        RETURN e
        ORDER BY e.createdAt ASC
        """,
    )
    fun findAllByInstanceAndStep(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String,
        @Param("workflowId") workflowId: String,
        @Param("stepId") stepId: String,
    ): List<WorkflowEvidenceNode>

    /** The evidence carrying an idempotency key, or `null`. */
    @Query(
        """
        MATCH (e:WorkflowEvidence)
        WHERE e.organizationId = ${'$'}organizationId
          AND e.workstreamId = ${'$'}workstreamId
          AND e.namespaceId = ${'$'}namespaceId
          AND e.workflowId = ${'$'}workflowId
          AND e.idempotencyKey = ${'$'}idempotencyKey
        RETURN e
        LIMIT 1
        """,
    )
    fun findByIdempotencyKey(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String,
        @Param("workflowId") workflowId: String,
        @Param("idempotencyKey") idempotencyKey: String,
    ): WorkflowEvidenceNode?
}

interface SpringDataNeo4jHumanInteractionRepository : Neo4jRepository<HumanInteractionNode, String> {

    /** Every interaction of an instance, oldest first. */
    @Query(
        """
        MATCH (h:HumanInteraction)
        WHERE h.organizationId = ${'$'}organizationId
          AND h.workstreamId = ${'$'}workstreamId
          AND h.namespaceId = ${'$'}namespaceId
          AND h.workflowId = ${'$'}workflowId
        RETURN h
        ORDER BY h.createdAt ASC
        """,
    )
    fun findAllByInstance(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String,
        @Param("workflowId") workflowId: String,
    ): List<HumanInteractionNode>

    /** Compare-and-swaps an interaction at [expectedRevision]. */
    @Query(
        """
        MATCH (h:HumanInteraction {id: ${'$'}id})
        WHERE h.revision = ${'$'}expectedRevision
        SET h.status = ${'$'}status,
            h.revision = ${'$'}revision,
            h.payload = ${'$'}payload,
            h.updatedAt = ${'$'}updatedAt
        RETURN count(h) AS updated
        """,
    )
    fun casUpdate(
        @Param("id") id: String,
        @Param("expectedRevision") expectedRevision: Int,
        @Param("status") status: String,
        @Param("revision") revision: Int,
        @Param("payload") payload: String,
        @Param("updatedAt") updatedAt: Instant,
    ): Long
}

interface SpringDataNeo4jHumanInteractionEventRepository : Neo4jRepository<HumanInteractionEventNode, String> {

    /** The append-only interaction journal of an instance, oldest first. */
    @Query(
        """
        MATCH (e:HumanInteractionEvent)
        WHERE e.organizationId = ${'$'}organizationId
          AND e.workstreamId = ${'$'}workstreamId
          AND e.namespaceId = ${'$'}namespaceId
          AND e.workflowId = ${'$'}workflowId
        RETURN e
        ORDER BY e.createdAt ASC
        """,
    )
    fun findAllByInstance(
        @Param("organizationId") organizationId: String,
        @Param("workstreamId") workstreamId: String,
        @Param("namespaceId") namespaceId: String,
        @Param("workflowId") workflowId: String,
    ): List<HumanInteractionEventNode>
}
