package io.whozoss.factory.run

import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.run.domain.RunFragmentRecord
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.domain.RunStatuses
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.sdk.spi.FactoryFlowSelector
import io.whozoss.factory.sdk.spi.FactoryRunLifecycleObserver
import io.whozoss.factory.workflow.domain.WorkflowEvidenceItem
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.persistence.EvidenceAppendResult
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunResult
import io.whozoss.factory.workflow.service.SessionStepState
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * WZ-36379: when a user Stop cancels a fragment of a parent Run, the parent Run becomes `cancelled`
 * too. It is a final decision of the user, never a fragment verdict: the selector is not consulted,
 * no next fragment is created, and replaying the notification is a no-op.
 */
class RunServiceCancelledFragmentTest {

    private val scope = TenantScope("org", "ws")
    private val namespace = "ns"
    private val runId = "run-1"
    private val fragmentId = "run-1__f1"

    private val runs = mockk<RunRepository>()
    private val workflows = mockk<WorkflowRepository>()
    private val evidence = mockk<WorkflowEvidenceRepository>()
    private val interactions = mockk<HumanInteractionRepository>()
    private val selector = mockk<FactoryFlowSelector>()
    private val observer = mockk<FactoryRunLifecycleObserver>(relaxed = true)

    private var stored = RunRecord(
        namespaceId = namespace,
        runId = runId,
        runType = "test-run",
        selectorVersion = "1.0.0",
        title = "A run",
        status = RunStatuses.RUNNING,
        revision = 1,
        fragments = listOf(
            RunFragmentRecord(
                fragmentId = fragmentId,
                ordinal = 1,
                flowType = "test.flow",
                status = WorkflowStatuses.RUNNING,
            ),
        ),
    )
    private val appended = mutableListOf<WorkflowEvidenceItem>()

    private fun service(): RunService {
        every { runs.find(scope, namespace, runId) } answers { stored }
        every { runs.update(scope, namespace, runId, any(), any()) } answers {
            val expected = arg<Int>(3)
            val next = arg<RunRecord>(4)
            (expected == stored.revision).also { if (it) stored = next }
        }
        every { workflows.findInstance(scope, namespace, fragmentId) } returns WorkflowInstanceRecord(
            namespaceId = namespace,
            workflowId = fragmentId,
            revision = 1,
            status = "active",
            creationCommandHash = "hash",
            instance = mapOf("runId" to runId),
            projection = emptyMap(),
        )
        every { evidence.list(any(), any(), any(), any()) } returns emptyList()
        every { interactions.list(any(), any(), any(), any()) } returns emptyList()
        val item = slot<WorkflowEvidenceItem>()
        every { evidence.append(any(), any(), any(), capture(item)) } answers {
            appended += item.captured
            EvidenceAppendResult.Created(item.captured)
        }
        return RunService(
            runRepository = runs,
            workflowRepository = workflows,
            evidenceRepository = evidence,
            interactionRepository = interactions,
            sessionDefinitionCatalog = mockk(),
            registeredSelectors = listOf(selector),
            registeredObservers = listOf(observer),
        )
    }

    private fun cancelledSession() = SessionRunResult(
        namespace,
        fragmentId,
        WorkflowStatuses.CANCELLED,
        listOf(SessionStepState("A", WorkflowStatuses.CANCELLED), SessionStepState("B", WorkflowStatuses.BLOCKED)),
    )

    @Test
    fun `a cancelled fragment seals the parent Run as cancelled without consulting the selector or creating a fragment`() {
        val service = service()

        val sealed = service.onFragmentSessionUpdated(scope, namespace, fragmentId, cancelledSession(), Path.of("."))!!

        assertThat(sealed.status).isEqualTo(RunStatuses.CANCELLED)
        assertThat(RunStatuses.isTerminal(sealed.status)).isTrue()
        // No next fragment: the history keeps the single stopped one, itself `cancelled`.
        assertThat(sealed.fragments).hasSize(1)
        assertThat(sealed.fragments.single().status).isEqualTo(WorkflowStatuses.CANCELLED)
        assertThat(sealed.fragments.single().completedAt).isNotNull()
        // The decision is sealed under the fragment id and explains itself.
        val decision = sealed.decisions.getValue(fragmentId)
        assertThat(decision.decisionType).isEqualTo("cancelled")
        assertThat(decision.fragmentId).isNull()
        assertThat(decision.reason).isEqualTo("USER_CANCELLED: fragment $fragmentId stopped by the user")
        assertThat(sealed.revision).isEqualTo(2)
        // The selector never decided anything: not even a call to reduce/next/start.
        confirmVerified(selector)
        // The audit evidence of the decision is persisted.
        val audit = appended.single { it.kind == "run-flow-decision" }
        assertThat(audit.outcome).isEqualTo("cancelled")
        assertThat(audit.facts["decisionKey"]).isEqualTo(fragmentId)
        assertThat(audit.idempotencyKey).isEqualTo("run-decision:$runId:$fragmentId")
        // The run is finished for the lifecycle observers.
        verify(exactly = 1) { observer.onRunFinished(any(), RunStatuses.CANCELLED) }
    }

    @Test
    fun `replaying the notification of a cancelled fragment is a no-op`() {
        val service = service()
        service.onFragmentSessionUpdated(scope, namespace, fragmentId, cancelledSession(), Path.of("."))
        val afterFirst = stored
        val evidenceCount = appended.size

        val replayed = service.onFragmentSessionUpdated(scope, namespace, fragmentId, cancelledSession(), Path.of("."))!!

        // The existing `decisions.containsKey(fragmentId)` guard answers before anything is written.
        assertThat(replayed).isEqualTo(afterFirst)
        assertThat(stored).isEqualTo(afterFirst)
        assertThat(appended).hasSize(evidenceCount)
        verify(exactly = 1) { runs.update(scope, namespace, runId, any(), any()) }
        confirmVerified(selector)
    }
}
