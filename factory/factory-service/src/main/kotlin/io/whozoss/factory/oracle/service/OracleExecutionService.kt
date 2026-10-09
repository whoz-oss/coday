package io.whozoss.factory.oracle.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.oracle.domain.OracleDefinition
import io.whozoss.factory.oracle.domain.OracleExecution
import io.whozoss.factory.oracle.domain.OracleExecutionStatus
import io.whozoss.factory.oracle.domain.OracleNotFoundException
import io.whozoss.factory.oracle.persistence.OracleExecutionRepository
import io.whozoss.factory.oracle.publisher.OracleArtifactPublisher
import io.whozoss.factory.oracle.publisher.OracleEvidencePublisher
import io.whozoss.factory.oracle.registry.OracleDefinitionRegistry
import io.whozoss.factory.persistence.TenantScope
import mu.KotlinLogging
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Command to run one oracle against a workflow step. */
data class OracleRunCommand(
    val workflowId: String,
    val stepId: String,
    val oracleId: String,
    val namespaceId: String,
    val idempotencyKey: String? = null,
)

/** Outcome of [OracleExecutionService.run]. */
data class OracleRunResult(
    val workflowId: String,
    val stepId: String,
    val oracleId: String,
    val executionId: String,
    val status: OracleExecutionStatus,
    val revision: Int,
    val outcome: String,
    val evidenceId: String?,
    val artifactId: String?,
    val created: Boolean,
    val idempotent: Boolean,
)

/**
 * Domain service of the ORACLES aggregate: it starts an execution, terminalizes
 * it and links the evidence / artifact it produced.
 *
 * Actual process spawning is deliberately out of scope for this aggregate port
 * (the run is *recorded*): the service owns the durable aggregate and its
 * optimistic-locking transitions. Evidence and artifact publication flow through
 * the [OracleEvidencePublisher] / [OracleArtifactPublisher] ports, which are
 * optional — an absent implementation simply leaves the corresponding link
 * unset.
 */
@Service
class OracleExecutionService(
    private val registry: OracleDefinitionRegistry,
    private val repository: OracleExecutionRepository,
    private val objectMapper: ObjectMapper,
    private val artifactPublisher: ObjectProvider<OracleArtifactPublisher>,
    private val evidencePublisher: ObjectProvider<OracleEvidencePublisher>,
) {

    private val logger = KotlinLogging.logger {}

    /**
     * Start and terminalize one oracle run.
     *
     * When [OracleRunCommand.idempotencyKey] is set and an execution already
     * exists for it in the caller's scope, that execution is replayed
     * (`created = false`, `idempotent = true`) instead of running again.
     */
    @Transactional
    fun run(scope: TenantScope, command: OracleRunCommand): OracleRunResult {
        val definition = registry.get(command.oracleId)
            ?: throw OracleNotFoundException("Oracle '${command.oracleId}' not found")

        command.idempotencyKey?.let { key ->
            repository.findByIdempotencyKey(scope, key)?.let { existing ->
                logger.debug { "Replaying idempotent oracle run ${existing.executionId} for key '$key'" }
                return existing.toResult(command, created = false, idempotent = true)
            }
        }

        val executionId = UUID.randomUUID().toString()
        val payload = OracleRunPayload(
            oracleId = definition.id,
            oracleVersion = definition.version,
            stepId = command.stepId,
            idempotencyKey = command.idempotencyKey,
        ).let { objectMapper.writeValueAsString(it) }

        val running = OracleExecution(
            organizationId = scope.organizationId,
            workstreamId = scope.workstreamId,
            namespaceId = command.namespaceId,
            workflowId = command.workflowId,
            executionId = executionId,
            oracleId = definition.id,
            status = OracleExecutionStatus.RUNNING,
            revision = 1,
            payload = payload,
        )
        repository.save(scope, running)

        val evidenceId = publishEvidence(
            scope,
            command,
            definition,
            outcome = OracleExecutionStatus.SUCCEEDED.dbValue,
        )
        val terminal = terminalize(
            scope = scope,
            namespaceId = command.namespaceId,
            workflowId = command.workflowId,
            executionId = executionId,
            status = OracleExecutionStatus.SUCCEEDED,
            expectedRevision = running.revision,
            artifactId = null,
            evidenceId = evidenceId,
        )
        return terminal.toResult(command, created = true, idempotent = false)
    }

    /**
     * Compare-and-swap an execution to a terminal status.
     *
     * Runs in a single transaction: the status/revision update and the artifact
     * publication are committed together (Amendment 5 upload-then-commit). A
     * revision mismatch surfaces as
     * [io.whozoss.factory.error.RevisionConflictException] (`REVISION_CONFLICT`,
     * HTTP 409).
     */
    @Transactional
    fun terminalize(
        scope: TenantScope,
        namespaceId: String,
        workflowId: String,
        executionId: String,
        status: OracleExecutionStatus,
        expectedRevision: Int,
        artifactId: String? = null,
        evidenceId: String? = null,
    ): OracleExecution {
        val updated = repository.updateStatus(
            scope = scope,
            namespaceId = namespaceId,
            workflowId = workflowId,
            executionId = executionId,
            status = status,
            expectedRevision = expectedRevision,
            artifactId = artifactId,
            evidenceId = evidenceId,
        )
        if (artifactId != null) {
            artifactPublisher.getIfAvailable()?.publishArtifact(scope, namespaceId, workflowId, artifactId)
        }
        return updated
    }

    private fun publishEvidence(
        scope: TenantScope,
        command: OracleRunCommand,
        definition: OracleDefinition,
        outcome: String,
    ): String? {
        val publisher = evidencePublisher.getIfAvailable() ?: return null
        return try {
            publisher.recordEvidence(
                scope = scope,
                namespaceId = command.namespaceId,
                workflowId = command.workflowId,
                stepId = command.stepId,
                oracleId = definition.id,
                outcome = outcome,
                facts = mapOf(
                    "oracleId" to definition.id,
                    "oracleVersion" to definition.version,
                    "stepId" to command.stepId,
                    "outcome" to outcome,
                ),
            )
        } catch (exception: RuntimeException) {
            logger.warn(exception) { "Evidence publication failed for oracle '${definition.id}'" }
            null
        }
    }

    private fun OracleExecution.toResult(
        command: OracleRunCommand,
        created: Boolean,
        idempotent: Boolean,
    ): OracleRunResult = OracleRunResult(
        workflowId = command.workflowId,
        stepId = command.stepId,
        oracleId = oracleId,
        executionId = executionId,
        status = status,
        revision = revision,
        outcome = status.dbValue,
        evidenceId = evidenceId,
        artifactId = artifactId,
        created = created,
        idempotent = idempotent,
    )
}

/** JSONB payload recorded for an execution, including its idempotency key. */
data class OracleRunPayload(
    val oracleId: String,
    val oracleVersion: String,
    val stepId: String,
    val idempotencyKey: String? = null,
)
