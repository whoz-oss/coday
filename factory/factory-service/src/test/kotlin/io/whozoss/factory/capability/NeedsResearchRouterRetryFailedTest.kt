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
import io.whozoss.factory.agentattempt.domain.StepQuestionLimits
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
 * Unit tests of the governed HUMAN retry of a FAILED agent step ([NeedsResearchRouter.retryFailed]) and of the
 * successor identity it creates. The worktree hand-over itself is DurableAgentAttemptService's job and is proven
 * against a real Neo4j in `FailedStepRetryIntegrationTest`; here the router is only checked to delegate
 * to the transactional hand-over and never to release anything.
 */
class NeedsResearchRouterRetryFailedTest {

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
        name = "Implement",
        responsibility = WorkflowStepResponsibility(ResponsibilityKind.AGENT, "builder"),
        dependsOn = emptyList(),
    )

    private val predecessor = DurableAgentAttempt(
        attemptId = "att-1",
        caseId = "case-A",
        namespaceId = namespace,
        workflowId = workflowId,
        stepId = "A",
        attemptNumber = 1,
        agentName = "builder",
        capabilityToken = "old-token",
        brief = "Use Factory attemptId 'att-1' and Idempotency-Key 'factory:att-1:result'.",
        contextEnvelope = """{"schemaVersion":1,"attemptNumber":1,"briefHash":"sha256:old","inputs":{}}""",
        expectedAmendmentSeq = 4L,
        environmentRef = "env-1",
        expectedEnvironmentRevision = 2,
        rootCaseId = "root",
        parentCaseId = "root",
        status = AgentAttemptStatus.FAILED,
        failureCode = "FACTORY_STEP_RESULT_FAILED",
    )

    private val previous = FailedAttemptSummary(
        resultId = "result-1",
        failureCode = "FACTORY_STEP_RESULT_FAILED",
        summary = "The build is red",
    )

    private val persistedEvidence = mutableListOf<WorkflowEvidenceItem>()
    private val registered = slot<DurableAgentAttempt>()
    private val handedOverFrom = slot<DurableAgentAttempt>()
    private val identity = slot<AgentStepResultCapabilityIdentity>()

    private fun stubAll() {
        every { attempts.nextAttemptNumber(scope, namespace, workflowId, "A") } returns 2
        every { evidence.append(scope, namespace, workflowId, any()) } answers {
            val item = arg<WorkflowEvidenceItem>(3)
            persistedEvidence.add(item)
            EvidenceAppendResult.Created(item)
        }
        every { stepAttempts.exists(scope, namespace, workflowId, "A", any()) } returns false
        every { stepAttempts.insert(scope, any()) } just Runs
        every { resultService.issue(scope, capture(identity), any(), any()) } returns IssuedCapability("new-token", "2026-01-01T00:15:00Z")
        every {
            attempts.registerRetryTransferringOccupancy(scope, capture(handedOverFrom), capture(registered), any(), any())
        } answers { registered.captured }
    }

    private fun retry(
        from: DurableAgentAttempt = predecessor,
        result: FailedAttemptSummary = previous,
        context: String = "Use the staging database.",
    ) = router.retryFailed(scope, namespace, workflowId, step, from, result, context, "alice")

    @Test
    fun `the successor keeps the case family, gets attempt number N+1, its own capability and a brief with its own ids`() {
        stubAll()

        val successorId = retry()

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

        // Its OWN capability, bound to ITS brief; never the predecessor's token.
        assertThat(successor.capabilityToken).isEqualTo("new-token").isNotEqualTo("old-token")
        assertThat(identity.captured.attemptId).isEqualTo(successorId)
        assertThat(identity.captured.briefHash).isEqualTo(CanonicalJsonHash.sha256(successor.brief!!))

        val brief = successor.brief
        assertThat(brief).doesNotContain("att-1")
        assertThat(brief).contains("attemptId '$successorId'").contains("factory:$successorId:result")

        // The envelope is re-pinned to the new brief and attempt number.
        val envelope = mapper.readTree(successor.contextEnvelope)
        assertThat(envelope.path("briefHash").asText()).isEqualTo(CanonicalJsonHash.sha256(brief))
        assertThat(envelope.path("attemptNumber").asInt()).isEqualTo(2)
    }

    @Test
    fun `the brief carries the previous result and the fenced engineer text`() {
        stubAll()

        retry()

        val brief = registered.captured.brief!!
        assertThat(brief).contains("## Retry of a failed attempt")
        assertThat(brief).contains("Previous result: The build is red")
        assertThat(brief).contains("<<<\nUse the staging database.\n>>>")
        assertThat(brief).doesNotContain(NeedsResearchRouter.RESULT_UNAVAILABLE_SENTENCE)
    }

    @Test
    fun `without a previous result the brief says it could not be retrieved`() {
        stubAll()

        retry(result = FailedAttemptSummary(resultId = null, failureCode = null, summary = null))

        val brief = registered.captured.brief!!
        assertThat(brief).contains("The previous result could not be retrieved.")
        assertThat(brief).doesNotContain("Previous result:")
    }

    @Test
    fun `a blank engineer text adds no fenced section`() {
        stubAll()

        retry(context = "")

        assertThat(registered.captured.brief).doesNotContain("<<<").doesNotContain("Information supplied by the engineer")
    }

    @Test
    fun `the resumption context holds identifiers only and links the audit item`() {
        stubAll()

        retry(context = "Use \"main\" and C:\\repo")

        val context = mapper.readTree(registered.captured.resumptionContext)
        assertThat(context.path("reason").asText()).isEqualTo("FAILED_STEP_RETRY")
        assertThat(context.path("previousAttemptId").asText()).isEqualTo("att-1")
        assertThat(context.path("previousResultId").asText()).isEqualTo("result-1")
        assertThat(context.path("failureCode").asText()).isEqualTo("FACTORY_STEP_RESULT_FAILED")
        assertThat(context.path("retryEvidenceId").asText()).isEqualTo(persistedEvidence.single().evidenceId)
        // The engineer text and the previous summary live in the audit item and the brief, never in this JSON.
        assertThat(context.has("additionalContext")).isFalse()
        assertThat(context.has("summary")).isFalse()
    }

    @Test
    fun `a 4000 character multibyte text and a huge previous summary can never exceed the byte bound`() {
        stubAll()
        // 4000 characters of 3 bytes each = 12 000 bytes, above RESUMPTION_CONTEXT_BYTES (8192).
        val multibyte = "\u20ac".repeat(4_000)
        assertThat(multibyte.toByteArray(Charsets.UTF_8).size).isGreaterThan(StepQuestionLimits.RESUMPTION_CONTEXT_BYTES)

        retry(
            result = FailedAttemptSummary("result-1", "FACTORY_STEP_RESULT_FAILED", "\u20ac".repeat(50_000)),
            context = multibyte,
        )

        val context = registered.captured.resumptionContext!!
        assertThat(context.toByteArray(Charsets.UTF_8).size).isLessThanOrEqualTo(StepQuestionLimits.RESUMPTION_CONTEXT_BYTES)
        // The full text is still durably audited and handed to the agent.
        assertThat(persistedEvidence.single().facts["additionalContext"]).isEqualTo(multibyte)
        assertThat(registered.captured.brief).contains(multibyte)
    }

    @Test
    fun `one audit item is appended with the actor, the predecessor and the successor`() {
        stubAll()

        val successorId = retry()

        val audit = persistedEvidence.single()
        assertThat(audit.kind).isEqualTo(NeedsResearchRouter.FAILED_STEP_RETRY_EVIDENCE_KIND)
        assertThat(audit.outcome).isEqualTo("accepted")
        assertThat(audit.source?.get("kind")).isEqualTo("factory-failed-step-retry")
        assertThat(audit.source?.get("actorId")).isEqualTo("alice")
        assertThat(audit.facts["attemptId"]).isEqualTo("att-1")
        assertThat(audit.facts["successorAttemptId"]).isEqualTo(successorId)
        assertThat(audit.facts["previousFailureCode"]).isEqualTo("FACTORY_STEP_RESULT_FAILED")
        assertThat(audit.facts["previousResultId"]).isEqualTo("result-1")
        assertThat(audit.facts["additionalContext"]).isEqualTo("Use the staging database.")
        assertThat(audit.idempotencyKey).isEqualTo("failed-step-retry:$successorId")
    }

    @Test
    fun `the successor is registered through the transactional hand-over of the failed predecessor and nothing else`() {
        stubAll()

        retry()

        // The predecessor reaches the hand-over untouched, so that it can fence the transfer on its exact handle.
        assertThat(handedOverFrom.captured).isEqualTo(predecessor)
        verify(exactly = 1) { attempts.registerRetryTransferringOccupancy(scope, any(), any(), any(), any()) }
        // A plain registerRetry, finalize or supersede would bypass or rewrite the hand-over: none must happen.
        verify(exactly = 0) { attempts.registerRetry(any(), any(), any()) }
        verify(exactly = 0) { attempts.finalize(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { attempts.supersede(any(), any(), any(), any(), any(), any(), any()) }
    }
}
