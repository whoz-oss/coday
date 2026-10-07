package io.whozoss.factory.planchange

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.error.FactoryException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.planchange.domain.DependencyChange
import io.whozoss.factory.planchange.domain.DependencyOp
import io.whozoss.factory.planchange.domain.PlanChangeDecisionStatus
import io.whozoss.factory.planchange.domain.PlanChangeKind
import io.whozoss.factory.planchange.domain.PlanChangeProposalType
import io.whozoss.factory.planchange.domain.PlanChangeSubmitCommand
import io.whozoss.factory.planchange.persistence.Neo4jPlanChangeProposalRepository
import io.whozoss.factory.planchange.service.PlanChangeProposalService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Integration tests of the append-only, tenant-scoped plan-change proposal store
 * and the service orchestration on top of it (idempotency, deterministic
 * classification, immutable decision log).
 *
 * Runs against the in-process Neo4j harness via [Neo4jDomainIntegrationTest];
 * the graph is cleared before each test by the shared fixture.
 */
class PlanChangeProposalRepositoryIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var repository: Neo4jPlanChangeProposalRepository

    @Autowired
    private lateinit var service: PlanChangeProposalService

    private fun submitCommand(
        workflowId: String = "wf-${UUID.randomUUID().toString().take(8)}",
        idempotencyKey: String = "key-${UUID.randomUUID().toString().take(8)}",
        type: PlanChangeProposalType = PlanChangeProposalType.DEPENDENCY,
        summary: String = "replan probe",
    ): PlanChangeSubmitCommand =
        PlanChangeSubmitCommand(
            workflowId = workflowId,
            namespaceId = NAMESPACE_ID,
            expectedRevision = 1,
            reasonCode = "ORACLE_FAILURE",
            summary = summary,
            proposalType = type,
            affectedStepIds = listOf("step-a", "step-b"),
            proposedDependencyChanges = if (type == PlanChangeProposalType.DEPENDENCY) {
                listOf(DependencyChange(DependencyOp.ADD, "step-a", "step-b"))
            } else {
                emptyList()
            },
            proposedScopeChanges = null,
            evidenceRefs = listOf("evidence-1"),
            idempotencyKey = idempotencyKey,
        )

    @Test
    fun `create then read a proposal within the tenant scope`() {
        val result = service.submit(scope, "tester", submitCommand())

        assertThat(result.idempotent).isFalse()
        val proposal = result.proposal
        assertThat(proposal.proposalId).startsWith("pcp-")
        assertThat(proposal.organizationId).isEqualTo(scope.organizationId)
        assertThat(proposal.workstreamId).isEqualTo(scope.workstreamId)
        assertThat(proposal.namespaceId).isEqualTo(NAMESPACE_ID)
        assertThat(proposal.kind).isEqualTo(PlanChangeKind.DEPENDENCY_CHANGE_PROPOSAL)
        assertThat(proposal.recommendedVerdict).isEqualTo(PlanChangeDecisionStatus.GATE_REQUIRED)
        assertThat(proposal.currentStatus).isEqualTo(PlanChangeDecisionStatus.PENDING_VALIDATION)
        assertThat(proposal.revision).isEqualTo(1)

        val fetched = repository.findById(scope, NAMESPACE_ID, proposal.workflowId, proposal.proposalId)
        assertThat(fetched).isNotNull
        assertThat(fetched!!.proposalId).isEqualTo(proposal.proposalId)
        assertThat(fetched.proposedDependencyChanges)
            .containsExactly(DependencyChange(DependencyOp.ADD, "step-a", "step-b"))
        assertThat(fetched.evidenceRefs).containsExactly("evidence-1")
    }

    @Test
    fun `reads are constrained by the tenant scope`() {
        val result = service.submit(scope, "tester", submitCommand())
        val proposal = result.proposal

        val otherOrg = TenantScope("other-org", WORKSTREAM_ID)
        assertThat(repository.findById(otherOrg, NAMESPACE_ID, proposal.workflowId, proposal.proposalId)).isNull()
        assertThat(repository.findByWorkflow(otherOrg, NAMESPACE_ID, proposal.workflowId)).isEmpty()

        val otherWorkstream = TenantScope(ORGANIZATION_ID, "ws-other")
        assertThat(repository.findById(otherWorkstream, NAMESPACE_ID, proposal.workflowId, proposal.proposalId))
            .isNull()
        assertThat(repository.findByWorkflow(otherWorkstream, NAMESPACE_ID, proposal.workflowId)).isEmpty()
    }

    @Test
    fun `an idempotent replay returns the persisted proposal, a divergent replay collides`() {
        val workflowId = "wf-${UUID.randomUUID().toString().take(8)}"
        val key = "key-${UUID.randomUUID().toString().take(8)}"
        val first = service.submit(scope, "tester", submitCommand(workflowId = workflowId, idempotencyKey = key))

        val replay = service.submit(scope, "tester", submitCommand(workflowId = workflowId, idempotencyKey = key))
        assertThat(replay.idempotent).isTrue()
        assertThat(replay.proposal.proposalId).isEqualTo(first.proposal.proposalId)
        assertThat(replay.decisions).hasSize(1)

        // A replay with the same idempotency tuple but a different payload collides.
        assertThatThrownBy {
            service.submit(
                scope,
                "tester",
                submitCommand(workflowId = workflowId, idempotencyKey = key, summary = "divergent payload"),
            )
        }
            .isInstanceOf(FactoryException::class.java)
            .satisfies({ error ->
                val factoryError = error as FactoryException
                assertThat(factoryError.statusCode).isEqualTo(409)
                assertThat(factoryError.errorCode).isEqualTo("IDEMPOTENCY_KEY_COLLISION")
            })

        // The collision did not create a duplicate.
        assertThat(repository.findByWorkflow(scope, NAMESPACE_ID, workflowId)).hasSize(1)
    }

    @Test
    fun `appendDecision writes an immutable monotone log and refreshes only the derived cache`() {
        val submitted = service.submit(scope, "tester", submitCommand())
        val proposal = submitted.proposal

        val (gated, first) = repository.appendDecision(
            scope = scope,
            namespaceId = NAMESPACE_ID,
            workflowId = proposal.workflowId,
            proposalId = proposal.proposalId,
            status = PlanChangeDecisionStatus.GATE_REQUIRED,
            actorId = "gate-keeper",
            reason = "structural change needs human review",
        )
        assertThat(first.sequence).isEqualTo(2)
        assertThat(first.status).isEqualTo(PlanChangeDecisionStatus.GATE_REQUIRED)
        assertThat(gated.currentStatus).isEqualTo(PlanChangeDecisionStatus.GATE_REQUIRED)
        assertThat(gated.revision).isEqualTo(2)
        // The immutable payload is preserved.
        assertThat(gated.summary).isEqualTo(proposal.summary)
        assertThat(gated.proposedDependencyChanges).isEqualTo(proposal.proposedDependencyChanges)
        assertThat(gated.evidenceRefs).isEqualTo(proposal.evidenceRefs)
        assertThat(gated.createdAt).isEqualTo(proposal.createdAt)

        val (rejected, second) = repository.appendDecision(
            scope = scope,
            namespaceId = NAMESPACE_ID,
            workflowId = proposal.workflowId,
            proposalId = proposal.proposalId,
            status = PlanChangeDecisionStatus.REJECTED,
            actorId = "gate-keeper",
            reason = "denied",
        )
        assertThat(second.sequence).isEqualTo(3)
        assertThat(rejected.currentStatus).isEqualTo(PlanChangeDecisionStatus.REJECTED)
        assertThat(rejected.revision).isEqualTo(3)

        val log = repository.listDecisions(scope, NAMESPACE_ID, proposal.workflowId, proposal.proposalId)
        assertThat(log.map { it.sequence }).containsExactly(1L, 2L, 3L)
        assertThat(log.map { it.status }).containsExactly(
            PlanChangeDecisionStatus.PENDING_VALIDATION,
            PlanChangeDecisionStatus.GATE_REQUIRED,
            PlanChangeDecisionStatus.REJECTED,
        )
        assertThat(repository.latestDecision(scope, NAMESPACE_ID, proposal.workflowId, proposal.proposalId)?.status)
            .isEqualTo(PlanChangeDecisionStatus.REJECTED)
    }

    @Test
    fun `appendDecision on an unknown proposal is a PLAN_CHANGE_PROPOSAL_NOT_FOUND`() {
        assertThatThrownBy {
            repository.appendDecision(
                scope = scope,
                namespaceId = NAMESPACE_ID,
                workflowId = "wf-absent",
                proposalId = "pcp-absent",
                status = PlanChangeDecisionStatus.REJECTED,
                actorId = "gate-keeper",
            )
        }
            .isInstanceOf(FactoryException::class.java)
            .satisfies({ error ->
                val factoryError = error as FactoryException
                assertThat(factoryError.statusCode).isEqualTo(404)
                assertThat(factoryError.errorCode).isEqualTo("PLAN_CHANGE_PROPOSAL_NOT_FOUND")
            })
    }

    @Test
    fun `the status filter lists only matching proposals`() {
        val workflowId = "wf-${UUID.randomUUID().toString().take(8)}"
        val pending = service.submit(scope, "tester", submitCommand(workflowId = workflowId))
        val gated = service.submit(scope, "tester", submitCommand(workflowId = workflowId))
        repository.appendDecision(
            scope = scope,
            namespaceId = NAMESPACE_ID,
            workflowId = workflowId,
            proposalId = gated.proposal.proposalId,
            status = PlanChangeDecisionStatus.GATE_REQUIRED,
            actorId = "gate-keeper",
        )

        val all = repository.findByWorkflow(scope, NAMESPACE_ID, workflowId)
        assertThat(all.map { it.proposalId })
            .containsExactlyInAnyOrder(pending.proposal.proposalId, gated.proposal.proposalId)

        val onlyPending = repository.findByWorkflow(
            scope,
            NAMESPACE_ID,
            workflowId,
            PlanChangeDecisionStatus.PENDING_VALIDATION,
        )
        assertThat(onlyPending.map { it.proposalId }).containsExactly(pending.proposal.proposalId)

        val onlyGated = repository.findByWorkflow(
            scope,
            NAMESPACE_ID,
            workflowId,
            PlanChangeDecisionStatus.GATE_REQUIRED,
        )
        assertThat(onlyGated.map { it.proposalId }).containsExactly(gated.proposal.proposalId)
    }

    companion object {
        private const val NAMESPACE_ID = "ns-planchange"
    }
}
