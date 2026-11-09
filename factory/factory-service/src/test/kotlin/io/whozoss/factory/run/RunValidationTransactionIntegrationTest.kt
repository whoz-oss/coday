package io.whozoss.factory.run

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.sdk.spi.FindingSeverity
import io.whozoss.factory.sdk.spi.NewFinding
import io.whozoss.factory.sdk.spi.RunUpdate
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

class RunValidationTransactionIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var runRepository: RunRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var evidenceRepository: WorkflowEvidenceRepository
    @Autowired lateinit var interactionRepository: HumanInteractionRepository
    @Autowired lateinit var sessionDefinitionCatalog: SessionDefinitionCatalog
    @Autowired lateinit var transactionManager: PlatformTransactionManager
    @Autowired lateinit var objectMapper: ObjectMapper

    private val namespace = "8f8a2c10-9e3b-4c5a-8a11-validatortx01"

    private fun service() = RunService(
        runRepository = runRepository,
        workflowRepository = workflowRepository,
        evidenceRepository = evidenceRepository,
        interactionRepository = interactionRepository,
        sessionDefinitionCatalog = sessionDefinitionCatalog,
        transactionManager = transactionManager,
    )

    private fun insertRun(id: String) = runRepository.insert(
        scope,
        RunRecord(
            namespaceId = namespace,
            runId = id,
            runType = "validator-tx",
            selectorVersion = "1",
            title = id,
            domainState = mapOf("before" to true),
            domainRevision = 2,
        ),
    )

    @Test
    fun `join caller transaction without active transaction fails before writing`() {
        val initial = insertRun("mandatory")

        assertThatThrownBy {
            service().applyStepValidationUpdate(
                scope, namespace, initial.runId, "fragment-1",
                RunUpdate(
                    newDomainState = mapOf("after" to true),
                    expectedDomainRevision = initial.domainRevision,
                    addedFindings = listOf(NewFinding(code = "F", severity = FindingSeverity.MAJOR, message = "must rollback")),
                ),
                idempotencyKey = "validator:attempt-1:v",
                joinCallerTransaction = true,
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("caller transaction is required")

        val after = runRepository.find(scope, namespace, initial.runId)!!
        assertThat(after.revision).isEqualTo(initial.revision)
        assertThat(after.domainRevision).isEqualTo(initial.domainRevision)
        assertThat(after.domainState).isEqualTo(initial.domainState)
        assertThat(after.findings).isEmpty()
        assertThat(after.appliedValidationUpdates).isEmpty()
    }

    @Test
    fun `failure after validator update rolls back run and evidence then retry applies exactly once`() {
        val initial = insertRun("rollback-and-retry")
        val fragmentId = "fragment-rollback"
        val idempotencyKey = "validator:attempt-1:SpecValidator"
        val update = RunUpdate(
            newDomainState = mapOf("after" to true),
            expectedDomainRevision = initial.domainRevision,
            addedFindings = listOf(NewFinding(code = "SPEC", severity = FindingSeverity.MAJOR, message = "finding")),
        )
        val runService = service()
        val tx = TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

        assertThatThrownBy {
            tx.executeWithoutResult {
                runService.withRunWriteLock(scope, namespace, initial.runId) {
                    runService.applyStepValidationUpdate(
                        scope, namespace, initial.runId, fragmentId, update,
                        idempotencyKey = idempotencyKey,
                        joinCallerTransaction = true,
                    )
                    evidenceRepository.append(
                        scope, namespace, fragmentId,
                        WorkflowEvidenceItem(
                            evidenceId = UUID.randomUUID().toString(),
                            namespaceId = namespace,
                            workflowId = fragmentId,
                            stepId = "spec",
                            kind = "agent-result",
                            outcome = "pass",
                            source = mapOf("attemptId" to "attempt-1"),
                            facts = mapOf("status" to "PASS"),
                            idempotencyKey = "result:attempt-1",
                            createdAt = null,
                        ),
                    )
                    error("injected finalization failure")
                }
            }
        }.hasMessageContaining("injected finalization failure")

        val rolledBack = runRepository.find(scope, namespace, initial.runId)!!
        assertThat(rolledBack.revision).isEqualTo(initial.revision)
        assertThat(rolledBack.domainRevision).isEqualTo(initial.domainRevision)
        assertThat(rolledBack.domainState).isEqualTo(initial.domainState)
        assertThat(rolledBack.findings).isEmpty()
        assertThat(rolledBack.appliedValidationUpdates).isEmpty()
        assertThat(evidenceRepository.list(scope, namespace, fragmentId)).isEmpty()

        tx.executeWithoutResult {
            runService.withRunWriteLock(scope, namespace, initial.runId) {
                runService.applyStepValidationUpdate(
                    scope, namespace, initial.runId, fragmentId, update,
                    idempotencyKey = idempotencyKey,
                    joinCallerTransaction = true,
                )
                evidenceRepository.append(
                    scope, namespace, fragmentId,
                    WorkflowEvidenceItem(
                        evidenceId = UUID.randomUUID().toString(),
                        namespaceId = namespace,
                        workflowId = fragmentId,
                        stepId = "spec",
                        kind = "agent-result",
                        outcome = "pass",
                        source = mapOf("attemptId" to "attempt-1"),
                        facts = mapOf("status" to "PASS"),
                        idempotencyKey = "result:attempt-1",
                        createdAt = null,
                    ),
                )
            }
        }

        val committed = runRepository.find(scope, namespace, initial.runId)!!
        assertThat(committed.revision).isEqualTo(initial.revision + 1)
        assertThat(committed.domainRevision).isEqualTo(initial.domainRevision + 1)
        assertThat(committed.domainState).containsExactlyEntriesOf(mapOf("after" to true))
        assertThat(committed.findings).hasSize(1)
        assertThat(committed.appliedValidationUpdates).containsExactly(idempotencyKey)
        assertThat(evidenceRepository.list(scope, namespace, fragmentId)).hasSize(1)

        tx.executeWithoutResult {
            runService.applyStepValidationUpdate(
                scope, namespace, initial.runId, fragmentId, update,
                idempotencyKey = idempotencyKey,
                joinCallerTransaction = true,
            )
        }
        val replayed = runRepository.find(scope, namespace, initial.runId)!!
        assertThat(replayed.revision).isEqualTo(committed.revision)
        assertThat(replayed.domainRevision).isEqualTo(committed.domainRevision)
        assertThat(replayed.findings).hasSize(1)
    }
}
