package io.whozoss.factory.capability

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.factory.agentattempt.domain.AgentAttemptStatus
import io.whozoss.factory.agentattempt.domain.AgentStepResultCapabilityIdentity
import io.whozoss.factory.agentattempt.domain.CanonicalJsonHash
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.domain.IssuedCapability
import io.whozoss.factory.agentattempt.persistence.AgentStepAttemptRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workflow.domain.ResponsibilityKind
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowStepDefinition
import io.whozoss.factory.workflow.domain.WorkflowStepResponsibility
import io.whozoss.factory.workflow.persistence.EvidenceAppendResult
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests of the governed HUMAN resumption of a NEEDS_RESEARCH step
 * ([NeedsResearchRouter.resume]) and of the successor identity it creates.
 */
class NeedsResearchRouterResumeTest {

    private val scope = TenantScope("org", "ws")
    private val namespace = "ns"
    private val workflowId = "wf"
    private val attempts = mockk<DurableAgentAttemptService>()
    private val evidence = mockk<WorkflowEvidenceRepository>()
    private val resultService = mockk<AgentStepResultService>()
    private val stepAttempts = mockk<AgentStepAttemptRepository>()
    private val router = NeedsResearchRouter(attempts, evidence, resultService, stepAttempts)
    private val mapper = ObjectMapper()

    private val step = WorkflowStepDefinition(
        id = "A",
        name = "Design",
        responsibility = WorkflowStepResponsibility(ResponsibilityKind.AGENT, "architect"),
        dependsOn = emptyList(),
    )

    private val result = CapabilityOutcome.AgentNeedsResearch(
        stepId = "A",
        persona = "architect",
        attemptId = "att-1",
        resultId = "result-1",
        summary = "no read access to the repository",
    )

    private val predecessor = DurableAgentAttempt(
        attemptId = "att-1",
        caseId = "case-A",
        namespaceId = namespace,
        workflowId = workflowId,
        stepId = "A",
        attemptNumber = 1,
        agentName = "architect",
        capabilityToken = "old-token",
        brief = "Use Factory attemptId 'att-1' and Idempotency-Key 'factory:att-1:result'.",
        contextEnvelope = """{"schemaVersion":1,"attemptNumber":1,"briefHash":"sha256:old","inputs":{}}""",
        expectedAmendmentSeq = 4L,
        environmentRef = "env-1",
        expectedEnvironmentRevision = 2,
        rootCaseId = "root",
        parentCaseId = "root",
        status = AgentAttemptStatus.INDETERMINATE,
        failureCode = "AGENT_NEEDS_RESEARCH",
    )

    private val persistedEvidence = mutableListOf<WorkflowEvidenceItem>()
    private val registered = slot<DurableAgentAttempt>()
    private val identity = slot<AgentStepResultCapabilityIdentity>()

    private fun stubAll() {
        every { attempts.find(scope, namespace, workflowId, "A", "att-1") } returns predecessor
        every { attempts.nextAttemptNumber(scope, namespace, workflowId, "A") } returns 2
        every { evidence.append(scope, namespace, workflowId, any()) } answers {
            val item = arg<WorkflowEvidenceItem>(3)
            persistedEvidence.add(item)
            EvidenceAppendResult.Created(item)
        }
        every { stepAttempts.exists(scope, namespace, workflowId, "A", any()) } returns false
        every { stepAttempts.insert(scope, any()) } just Runs
        every { resultService.issue(scope, capture(identity), any(), any()) } returns IssuedCapability("new-token", "2026-01-01T00:15:00Z")
        every { attempts.registerRetry(scope, capture(registered), any()) } answers { registered.captured }
    }

    @Test
    fun `resume registers a brand-new attempt with its own capability and an enriched brief`() {
        stubAll()
        val context = "Access fixed.\nUse \"main\" and C:\\repo \u00e9\u00e8"

        val successorId = router.resume(scope, namespace, workflowId, step, result, context, "alice")

        assertThat(successorId).isEqualTo(CapabilityExecutionService.retryAttemptId(workflowId, "A", 2))
        val successor = registered.captured
        assertThat(successor.attemptId).isEqualTo(successorId)
        assertThat(successor.attemptNumber).isEqualTo(2)
        assertThat(successor.status).isEqualTo(AgentAttemptStatus.PENDING)
        assertThat(successor.ownerToken).isNull()

        // Same case family / environment / amendment pin as the predecessor.
        assertThat(successor.caseId).isEqualTo("case-A")
        assertThat(successor.rootCaseId).isEqualTo("root")
        assertThat(successor.parentCaseId).isEqualTo("root")
        assertThat(successor.environmentRef).isEqualTo("env-1")
        assertThat(successor.expectedEnvironmentRevision).isEqualTo(2)
        assertThat(successor.expectedAmendmentSeq).isEqualTo(4L)

        // The capability is the successor's OWN: never the predecessor's token, bound to the successor brief.
        assertThat(successor.capabilityToken).isEqualTo("new-token").isNotEqualTo("old-token")
        assertThat(identity.captured.attemptId).isEqualTo(successorId)
        assertThat(identity.captured.briefHash).isEqualTo(CanonicalJsonHash.sha256(successor.brief!!))
        verify(exactly = 1) { stepAttempts.insert(scope, match { it.attemptId == successorId }) }

        // The brief addresses the SUCCESSOR identifiers and actually carries the engineer's text.
        val brief = successor.brief!!
        assertThat(brief).doesNotContain("att-1")
        assertThat(brief).contains("attemptId '$successorId'").contains("factory:$successorId:result")
        assertThat(brief).contains(context).contains("no read access to the repository")

        // The frozen envelope is re-pinned to the new brief and attempt number.
        val envelope = mapper.readTree(successor.contextEnvelope)
        assertThat(envelope.path("briefHash").asText()).isEqualTo(CanonicalJsonHash.sha256(brief))
        assertThat(envelope.path("attemptNumber").asInt()).isEqualTo(2)

        // The resumption context is valid JSON that round-trips the awkward text exactly.
        val resumption = mapper.readTree(successor.resumptionContext)
        assertThat(resumption.path("additionalContext").asText()).isEqualTo(context)
        assertThat(resumption.path("previousAttemptId").asText()).isEqualTo("att-1")
        assertThat(resumption.path("previousResultId").asText()).isEqualTo("result-1")

        // Audit: one append-only resumption item, tied to the successor and the actor.
        val audit = persistedEvidence.single()
        assertThat(audit.kind).isEqualTo(NeedsResearchRouter.RESUMPTION_EVIDENCE_KIND)
        assertThat(audit.source?.get("actorId")).isEqualTo("alice")
        assertThat(audit.facts["successorAttemptId"]).isEqualTo(successorId)
        assertThat(resumption.path("resumptionEvidenceId").asText()).isEqualTo(audit.evidenceId)
    }

    @Test
    fun `resume leaves the predecessor attempt strictly untouched`() {
        stubAll()

        router.resume(scope, namespace, workflowId, step, result, "more info", "alice")

        // The mocks are strict: any finalize/supersede/cancel/transition of the predecessor would have thrown.
        verify(exactly = 1) { attempts.registerRetry(scope, any(), any()) }
        verify(exactly = 0) { attempts.finalize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { attempts.supersede(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a replayed audit item is reused so the resumption context links the persisted evidence id`() {
        stubAll()
        val existing = WorkflowEvidenceItem(
            "existing-evidence", namespace, workflowId, "A", NeedsResearchRouter.RESUMPTION_EVIDENCE_KIND,
            "accepted", null, emptyMap(), null, null,
        )
        every { evidence.append(scope, namespace, workflowId, any()) } returns EvidenceAppendResult.Idempotent(existing)

        router.resume(scope, namespace, workflowId, step, result, "more info", "alice")

        val resumption = mapper.readTree(registered.captured.resumptionContext)
        assertThat(resumption.path("resumptionEvidenceId").asText()).isEqualTo("existing-evidence")
    }

    @Test
    fun `an unknown predecessor attempt is refused before anything is written`() {
        every { attempts.find(scope, namespace, workflowId, "A", "att-1") } returns null

        org.assertj.core.api.Assertions.assertThatThrownBy {
            router.resume(scope, namespace, workflowId, step, result, "more info", "alice")
        }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { evidence.append(any(), any(), any(), any()) }
        verify(exactly = 0) { attempts.registerRetry(any(), any(), any()) }
    }

    @Test
    fun `the automatic re-arm also gets its own capability and successor brief`() {
        stubAll()

        val outcome = router.route(scope, namespace, workflowId, step, result) { searcherStep ->
            CapabilityExecution(
                outcome = CapabilityOutcome.AgentCompleted(searcherStep.id, "Searcher", "PASS", emptyMap()),
                attemptId = "searcher-attempt",
            )
        }

        assertThat(outcome.reArmed).isTrue()
        val successor = registered.captured
        assertThat(successor.capabilityToken).isEqualTo("new-token")
        assertThat(successor.brief).doesNotContain("att-1").contains(outcome.reArmedAttemptId!!)
        assertThat(identity.captured.briefHash).isEqualTo(CanonicalJsonHash.sha256(successor.brief!!))
    }
}
