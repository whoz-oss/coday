package io.whozoss.factory.environment.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.whozoss.factory.environment.domain.EnvironmentErrorCodes
import io.whozoss.factory.environment.domain.EnvironmentNotFoundException
import io.whozoss.factory.environment.domain.InvalidEnvironmentStateException
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.persistence.TenantScope
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Neo4j implementation of [WorkEnvironmentRepository].
 *
 * Replaces `JdbcWorkEnvironmentRepository`. The whole descriptor is stored as
 * its JSON text in the node `payload` (mirroring the former JSONB column); the
 * `status` and `revision` node properties are authoritative and override the
 * payload on read so the lifecycle status never drifts from the descriptor.
 */
@Repository
@Primary
class Neo4jWorkEnvironmentRepository(
    private val repository: SpringDataNeo4jWorkEnvironmentRepository,
    private val objectMapper: ObjectMapper,
) : WorkEnvironmentRepository {

    override fun insert(scope: TenantScope, environment: WorkEnvironment): WorkEnvironment {
        repository.save(environment.toNode(scope, environment.createdAt))
        return findByEnvironmentId(scope, environment.environmentId)
            ?: throw IllegalStateException("Environment '${environment.environmentId}' vanished after insert")
    }

    override fun findByEnvironmentId(scope: TenantScope, environmentId: String): WorkEnvironment? =
        repository
            .findById(WorkEnvironmentNode.compositeId(scope.organizationId, scope.workstreamId, environmentId))
            .orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }
            ?.let { toDomain(it) }

    override fun findLatestByWorkflowId(scope: TenantScope, workflowId: String): WorkEnvironment? =
        repository
            .findLatestByWorkflowId(scope.organizationId, scope.workstreamId, workflowId)
            ?.let { toDomain(it) }

    override fun list(scope: TenantScope): List<WorkEnvironment> =
        repository
            .findAllByScope(scope.organizationId, scope.workstreamId)
            .map { toDomain(it) }

    override fun updateState(
        scope: TenantScope,
        environment: WorkEnvironment,
        expectedRevision: Int,
        updatedAt: Instant,
    ): WorkEnvironment {
        val updated = repository.casUpdateState(
            id = WorkEnvironmentNode.compositeId(scope.organizationId, scope.workstreamId, environment.environmentId),
            expectedRevision = expectedRevision,
            status = environment.lifecycleState.dbValue,
            payload = serialize(environment),
            updatedAt = updatedAt,
        )
        if (updated == 0L) {
            val existing = findByEnvironmentId(scope, environment.environmentId)
            if (existing != null) {
                throw InvalidEnvironmentStateException(
                    "Revision conflict for environment '${environment.environmentId}': expected revision " +
                        "$expectedRevision, found ${existing.revision}",
                    details = mapOf("errorCode" to EnvironmentErrorCodes.INVALID_ENVIRONMENT_STATE),
                )
            }
            throw EnvironmentNotFoundException("Environment '${environment.environmentId}' not found")
        }
        return findByEnvironmentId(scope, environment.environmentId)
            ?: throw IllegalStateException("Environment '${environment.environmentId}' vanished after update")
    }

    private fun serialize(environment: WorkEnvironment): String = objectMapper.writeValueAsString(environment)

    private fun WorkEnvironment.toNode(scope: TenantScope, updatedAt: Instant): WorkEnvironmentNode =
        WorkEnvironmentNode(
            id = WorkEnvironmentNode.compositeId(scope.organizationId, scope.workstreamId, environmentId),
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            environmentId = environmentId,
            workflowId = workflowId,
            status = lifecycleState.dbValue,
            revision = revision,
            payload = serialize(this),
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun toDomain(node: WorkEnvironmentNode): WorkEnvironment {
        val parsed = objectMapper.readValue<WorkEnvironment>(node.payload)
        // The node properties are authoritative for the revision and the status.
        return parsed.copy(
            revision = node.revision,
            lifecycleState = WorkEnvironmentState.fromDbValue(node.status),
        )
    }
}
