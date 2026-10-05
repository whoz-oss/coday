package io.whozoss.factory.workflow.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.WorkflowCodeTransitionRecord
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowProjectionRecord
import io.whozoss.factory.workflow.domain.WorkflowStepStateRecord
import io.whozoss.factory.workflow.domain.WorkflowTransitionRequest
import io.whozoss.factory.workflow.domain.workflowException
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Neo4j implementation of [WorkflowRepository].
 *
 * Replaces the retired `JdbcWorkflowRepository` for every definition, instance,
 * transition, step-state and projection call. The durable surface is one node
 * label per former table, the composite tenant key is the node id, and optimistic
 * locking is enforced by graph-native compare-and-swap statements. This adapter is
 * the authoritative bean for [WorkflowRepository].
 */
@Repository
@Primary
class Neo4jWorkflowRepository(
    private val definitions: SpringDataNeo4jWorkflowDefinitionRepository,
    private val instances: SpringDataNeo4jWorkflowInstanceRepository,
    private val projections: SpringDataNeo4jWorkflowProjectionRepository,
    private val stepStates: SpringDataNeo4jWorkflowStepStateRepository,
    private val transitions: SpringDataNeo4jWorkflowTransitionRepository,
    private val transitionAppender: WorkflowTransitionAppender,
    private val codeTransitions: SpringDataNeo4jWorkflowCodeTransitionRepository,
    private val objectMapper: ObjectMapper,
) : WorkflowRepository {

    // ------------------------------------------------------------------
    // Definitions
    // ------------------------------------------------------------------

    override fun findDefinition(scope: TenantScope, workflowType: String, version: String): WorkflowDefinitionRecord? =
        definitions
            .findById(WorkflowDefinitionNode.compositeId(scope.organizationId, workflowType, version))
            .orElse(null)
            ?.toDomain(objectMapper)

    override fun listDefinitions(scope: TenantScope): List<WorkflowDefinitionRecord> =
        definitions.findAllByOrganization(scope.organizationId).map { it.toDomain(objectMapper) }

    override fun saveDefinition(scope: TenantScope, record: WorkflowDefinitionRecord) {
        definitions.save(WorkflowDefinitionNode.fromDomain(scope, record, objectMapper))
    }

    override fun deleteDefinition(scope: TenantScope, workflowType: String, version: String): Boolean {
        val id = WorkflowDefinitionNode.compositeId(scope.organizationId, workflowType, version)
        if (!definitions.existsById(id)) return false
        definitions.deleteById(id)
        return true
    }

    // ------------------------------------------------------------------
    // Governed instances
    // ------------------------------------------------------------------

    override fun findInstance(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowInstanceRecord? =
        readInstance(scope, namespaceId, workflowId)

    override fun listInstances(scope: TenantScope, namespaceId: String): List<WorkflowInstanceRecord> =
        instances
            .findAllByScopeAndNamespaceAndStatus(scope.organizationId, scope.workstreamId, namespaceId, "active")
            .map { it.toDomain(objectMapper) }

    override fun insertInstance(scope: TenantScope, record: WorkflowInstanceRecord): WorkflowInstanceRecord {
        val id = instanceId(scope, record.namespaceId, record.workflowId)
        val existing = instances.findById(id).orElse(null)
        if (existing != null) {
            if (existing.creationCommandHash != record.creationCommandHash) {
                throw workflowException(WorkflowErrorCodes.WORKFLOW_IDENTITY_CONFLICT)
            }
            return existing.toDomain(objectMapper)
        }
        instances.save(WorkflowInstanceNode.fromDomain(scope, record, objectMapper))
        return readInstance(scope, record.namespaceId, record.workflowId) ?: record
    }

    override fun updateInstance(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        expectedRevision: Int,
        next: WorkflowInstanceRecord,
    ): Boolean {
        val updated = instances.casUpdateInstance(
            id = instanceId(scope, namespaceId, workflowId),
            expectedRevision = expectedRevision,
            revision = next.revision,
            status = next.status,
            instance = objectMapper.writeJson(next.instance),
            projection = objectMapper.writeJson(next.projection),
            updatedAt = Instant.now(),
        )
        return updated > 0
    }

    override fun setInstanceStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        from: String,
        to: String,
    ): Boolean =
        instances.casUpdateInstanceStatus(
            id = instanceId(scope, namespaceId, workflowId),
            fromStatus = from,
            toStatus = to,
            updatedAt = Instant.now(),
        ) > 0

    override fun deleteInstance(scope: TenantScope, namespaceId: String, workflowId: String): Boolean {
        val id = instanceId(scope, namespaceId, workflowId)
        if (!instances.existsById(id)) return false
        instances.deleteById(id)
        return true
    }

    private fun readInstance(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowInstanceRecord? =
        instances
            .findById(instanceId(scope, namespaceId, workflowId))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }
            ?.toDomain(objectMapper)

    private fun instanceId(scope: TenantScope, namespaceId: String, workflowId: String): String =
        WorkflowInstanceNode.compositeId(scope.organizationId, scope.workstreamId, namespaceId, workflowId)

    // ------------------------------------------------------------------
    // Transition logs
    // ------------------------------------------------------------------

    override fun appendTransition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        transitionId: String,
        request: WorkflowTransitionRequest,
        fromStepId: String?,
        toStepId: String,
        payload: Map<String, Any?>,
    ) {
        val id = WorkflowTransitionNode.compositeId(
            scope.organizationId,
            scope.workstreamId,
            namespaceId,
            workflowId,
            transitionId,
        )
        val node = WorkflowTransitionNode.fromDomain(
            scope = scope,
            namespaceId = namespaceId,
            workflowId = workflowId,
            transitionId = transitionId,
            fromStepId = fromStepId,
            toStepId = toStepId,
            eventName = request.requestedStatus,
            payload = payload,
            objectMapper = objectMapper,
        )
        transitionAppender.appendIfAbsent(node)
    }

    override fun listTransitionTimestamps(scope: TenantScope, namespaceId: String, workflowId: String): List<String> =
        transitions
            .findAllByInstance(scope.organizationId, scope.workstreamId, namespaceId, workflowId)
            .map { it.createdAt.toString() }

    // ------------------------------------------------------------------
    // Per-step DAG state
    // ------------------------------------------------------------------

    override fun findStepStates(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
    ): List<WorkflowStepStateRecord> =
        stepStates
            .findAllByInstance(scope.organizationId, scope.workstreamId, namespaceId, workflowId)
            .map { it.toDomain(objectMapper) }

    override fun upsertStepState(scope: TenantScope, record: WorkflowStepStateRecord): WorkflowStepStateRecord {
        val id = WorkflowStepStateNode.compositeId(
            scope.organizationId,
            scope.workstreamId,
            record.namespaceId,
            record.workflowId,
            record.stepId,
        )
        val existing = stepStates.findById(id).orElse(null)
        val now = Instant.now()
        val node = if (existing == null) {
            WorkflowStepStateNode.fromDomain(scope, record, revision = 1, objectMapper = objectMapper)
        } else {
            existing.copy(
                status = record.status,
                payload = objectMapper.writeJson(record.payload),
                revision = existing.revision + 1,
                updatedAt = now,
            )
        }
        stepStates.save(node)
        return node.toDomain(objectMapper)
    }

    override fun updateStepStatus(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        expectedRevision: Int,
        nextStatus: String,
        payload: Map<String, Any?>,
    ): Boolean =
        stepStates.casUpdateStatus(
            id = WorkflowStepStateNode.compositeId(
                scope.organizationId,
                scope.workstreamId,
                namespaceId,
                workflowId,
                stepId,
            ),
            expectedRevision = expectedRevision,
            status = nextStatus,
            payload = objectMapper.writeJson(payload),
            updatedAt = Instant.now(),
        ) > 0

    override fun claimStep(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        stepId: String,
        fromStatuses: List<String>,
        payload: Map<String, Any?>,
    ): Boolean =
        stepStates.casClaimStep(
            id = WorkflowStepStateNode.compositeId(
                scope.organizationId,
                scope.workstreamId,
                namespaceId,
                workflowId,
                stepId,
            ),
            fromStatuses = fromStatuses,
            status = "running",
            payload = objectMapper.writeJson(payload),
            updatedAt = Instant.now(),
        ) > 0

    override fun appendCodeTransition(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        record: WorkflowCodeTransitionRecord,
    ) {
        val id = WorkflowCodeTransitionNode.compositeId(
            scope.organizationId,
            scope.workstreamId,
            namespaceId,
            workflowId,
            record.codeTransitionId,
        )
        if (codeTransitions.existsById(id)) return
        codeTransitions.save(
            WorkflowCodeTransitionNode.fromDomain(scope, namespaceId, workflowId, record, objectMapper),
        )
    }

    override fun listCodeTransitions(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
    ): List<WorkflowCodeTransitionRecord> =
        codeTransitions
            .findAllByInstance(scope.organizationId, scope.workstreamId, namespaceId, workflowId)
            .map { it.toDomain(objectMapper) }

    // ------------------------------------------------------------------
    // Declarative projection store
    // ------------------------------------------------------------------

    override fun findProjection(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
    ): WorkflowProjectionRecord? =
        readProjection(scope, namespaceId, workflowId)

    override fun listProjections(
        scope: TenantScope,
        namespaceId: String?,
        lifecycleState: String,
    ): List<WorkflowProjectionRecord> =
        projections
            .findAllByScopeAndLifecycle(
                scope.organizationId,
                scope.workstreamId,
                namespaceId?.takeIf { it.isNotBlank() },
                lifecycleState,
            )
            .map { it.toDomain(objectMapper) }

    override fun publishProjection(
        scope: TenantScope,
        record: WorkflowProjectionRecord,
        expectedRevision: Int?,
    ): ProjectionPublishResult {
        val existing = projections.findById(projectionId(scope, record.namespaceId, record.workflowId)).orElse(null)
        if (existing == null) {
            if (expectedRevision != null && expectedRevision != 0) {
                return ProjectionPublishResult.Conflict(WorkflowErrorCodes.REVISION_CONFLICT)
            }
            projections.save(
                WorkflowProjectionNode.fromDomain(
                    scope = scope,
                    record = record,
                    revision = 1,
                    lifecycleState = "active",
                    objectMapper = objectMapper,
                ),
            )
            return ProjectionPublishResult.Changed(record.copy(revision = 1, lifecycleState = "active"))
        }
        val current = existing.toDomain(objectMapper)
        if (current.lifecycleState == "purged") {
            return ProjectionPublishResult.Conflict(WorkflowErrorCodes.WORKFLOW_PURGED)
        }
        if (expectedRevision != null && expectedRevision != current.revision) {
            return ProjectionPublishResult.Conflict(WorkflowErrorCodes.REVISION_CONFLICT)
        }
        if (current.projectionHash == record.projectionHash && current.status == record.status) {
            return ProjectionPublishResult.Idempotent(current)
        }
        val nextRevision = current.revision + 1
        projections.save(
            WorkflowProjectionNode.fromDomain(
                scope = scope,
                record = record,
                revision = nextRevision,
                lifecycleState = current.lifecycleState,
                objectMapper = objectMapper,
                createdAt = existing.createdAt,
            ),
        )
        return ProjectionPublishResult.Changed(record.copy(revision = nextRevision))
    }

    override fun setProjectionLifecycle(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        from: List<String>,
        to: String,
    ): Boolean =
        projections.casUpdateLifecycle(
            id = projectionId(scope, namespaceId, workflowId),
            fromStates = from,
            toState = to,
            updatedAt = Instant.now(),
        ) > 0

    override fun deleteProjectionLifecycle(scope: TenantScope, namespaceId: String, workflowId: String): Boolean {
        val id = projectionId(scope, namespaceId, workflowId)
        if (!projections.existsById(id)) return false
        projections.deleteById(id)
        return true
    }

    private fun readProjection(scope: TenantScope, namespaceId: String, workflowId: String): WorkflowProjectionRecord? =
        projections
            .findById(projectionId(scope, namespaceId, workflowId))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }
            ?.toDomain(objectMapper)

    private fun projectionId(scope: TenantScope, namespaceId: String, workflowId: String): String =
        WorkflowProjectionNode.compositeId(scope.organizationId, scope.workstreamId, namespaceId, workflowId)
}
