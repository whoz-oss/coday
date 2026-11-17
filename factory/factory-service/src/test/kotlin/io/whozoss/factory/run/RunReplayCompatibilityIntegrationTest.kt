package io.whozoss.factory.run

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import java.nio.file.Path
import java.util.UUID
import org.junit.jupiter.api.io.TempDir
import org.springframework.transaction.PlatformTransactionManager
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.run.persistence.RunProvisioningIntentRepository
import io.whozoss.factory.run.service.ParentRunWorkspaceProvisioningService
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.run.domain.RunDecisionRecord
import io.whozoss.factory.run.domain.RunFragmentRecord
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.persistence.RunNode
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.persistence.SpringDataNeo4jRunRepository
import io.whozoss.factory.run.service.RunService
import io.whozoss.factory.run.service.RunStartCommand
import io.whozoss.factory.sdk.spi.FactoryFlowSelector
import io.whozoss.factory.sdk.spi.FlowDecision
import io.whozoss.factory.sdk.spi.RunUpdate
import io.whozoss.factory.sdk.spi.RunView
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowStatuses
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionDefinitionCatalog
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.pf4j.PluginManager
import org.springframework.beans.factory.annotation.Autowired

class RunReplayCompatibilityIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var runRepository: RunRepository
    @Autowired lateinit var runs: SpringDataNeo4jRunRepository
    @Autowired lateinit var workflowRepository: WorkflowRepository
    @Autowired lateinit var evidenceRepository: WorkflowEvidenceRepository
    @Autowired lateinit var interactionRepository: HumanInteractionRepository
    @Autowired lateinit var objectMapper: ObjectMapper

    @Autowired lateinit var environments: WorkEnvironmentRepository
    @Autowired lateinit var intents: RunProvisioningIntentRepository
    @Autowired lateinit var transactions: PlatformTransactionManager
    @TempDir lateinit var root: Path
    private fun provisioning(): ParentRunWorkspaceProvisioningService {
        val proxy = mockk<AgentOsProxyClient>(relaxed = true)
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        return ParentRunWorkspaceProvisioningService(runRepository, environments, intents, proxy, objectMapper, transactions)
    }
    private val namespace = "7f8a2c10-9e3b-4c5a-8a11-replaycompat1"
    private val runType = "replay-compat"
    private val flowType = "replay.flow"

    private fun selector(version: String) = object : FactoryFlowSelector {
        override fun runTypes() = setOf(runType)
        override fun selectorVersion() = version
        override fun start(run: RunView) = RunUpdate() to FlowDecision.Fail("unused", "unused")
        override fun next(run: RunView) = FlowDecision.Fail("unused", "unused")
    }

    private fun service(selector: FactoryFlowSelector, submitter: SessionRunSubmissionService? = null): RunService {
        val plugins = mockk<PluginManager>(relaxed = true)
        return RunService(
            runRepository, workflowRepository, evidenceRepository, interactionRepository,
            SessionDefinitionCatalog(objectMapper, plugins),
            sessionRunSubmissionService = submitter,
            registeredSelectors = listOf(selector),
            workspaceProvisioning = provisioning(),
        )
    }

    private fun definition(version: String, hash: String) = WorkflowDefinitionRecord(
        workflowType = flowType,
        version = version,
        definitionHash = hash,
        definition = mapOf(
            "schemaVersion" to "1",
            "workflowType" to flowType,
            "version" to version,
            "title" to "Replay",
            "steps" to listOf(mapOf("id" to "human", "name" to "Human", "responsibility" to mapOf("kind" to "human", "name" to "PO"), "dependsOn" to emptyList<String>())),
        ),
    )

    private fun persistedRun(
        id: String,
        selectorVersion: String = "1.0.0",
        flowVersions: Map<String, String> = mapOf(flowType to "1.0.0"),
        hashes: Map<String, String> = mapOf(flowType to "hash-v1"),
    ): RunRecord {
        val decision = RunDecisionRecord("start", "apply", flowType, "${id}__f1", "started", null, 0)
        val record = RunRecord(
                namespaceId = namespace,
                runId = id,
                runType = runType,
                selectorVersion = selectorVersion,
                title = id,
                rootCaseId = UUID.randomUUID().toString(),
                revision = 2,
                fragments = listOf(RunFragmentRecord("${id}__f1", 1, flowType, WorkflowStatuses.READY)),
                decisions = mapOf(RunService.START_DECISION_KEY to decision),
                flowVersions = flowVersions,
                flowDefinitionHashes = hashes,
            )
        // Explicitly seed authenticated intent through the real service, not a legacy bypass.
        provisioning().ensureReady(
            scope,
            record.copy(fragments = emptyList(), decisions = emptyMap(), flowVersions = emptyMap(), flowDefinitionHashes = emptyMap()),
            RunStartCommand(id, runType, id),
            null,
            "alice",
        )
        val current = runRepository.find(scope, namespace, id)!!
        runs.save(RunNode.fromDomain(scope, record.copy(revision = current.revision + 1), objectMapper))
        return runRepository.find(scope, namespace, id)!!
    }

    private fun replay(runId: String, selector: FactoryFlowSelector, submitter: SessionRunSubmissionService? = null) =
        service(selector, submitter).startRun(scope, namespace, RunStartCommand(runId, runType, runId), actorId = "alice")

    @Test
    fun `start replay refuses incompatible selector version before enqueue and preserves decisions`() {
        workflowRepository.saveDefinition(scope, definition("1.0.0", "hash-v1"))
        val before = persistedRun("selector-version")
        val submitter = mockk<SessionRunSubmissionService>(relaxed = true)

        assertThatThrownBy { replay(before.runId, selector("2.0.0"), submitter) }
            .hasMessageContaining("pinned to selector '1.0.0'")
        verify(exactly = 0) { submitter.submit(any(), any(), any(), any(), any(), any()) }
        assertThat(runRepository.find(scope, namespace, before.runId)!!.decisions).isEqualTo(before.decisions)
    }

    @Test
    fun `start replay refuses missing pinned flow version before enqueue and preserves decisions`() {
        workflowRepository.saveDefinition(scope, definition("2.0.0", "hash-v2"))
        val before = persistedRun("missing-flow")
        val submitter = mockk<SessionRunSubmissionService>(relaxed = true)

        assertThatThrownBy { replay(before.runId, selector("1.0.0"), submitter) }
            .hasMessageContaining("pinned to flow '$flowType@1.0.0'")
        verify(exactly = 0) { submitter.submit(any(), any(), any(), any(), any(), any()) }
        assertThat(runRepository.find(scope, namespace, before.runId)!!.decisions).isEqualTo(before.decisions)
    }

    @Test
    fun `start replay refuses changed hash for same pinned version before enqueue and preserves decisions`() {
        workflowRepository.saveDefinition(scope, definition("1.0.0", "different-hash"))
        val before = persistedRun("changed-hash")
        val submitter = mockk<SessionRunSubmissionService>(relaxed = true)

        assertThatThrownBy { replay(before.runId, selector("1.0.0"), submitter) }
            .hasMessageContaining("hash 'hash-v1'")
        verify(exactly = 0) { submitter.submit(any(), any(), any(), any(), any(), any()) }
        assertThat(runRepository.find(scope, namespace, before.runId)!!.decisions).isEqualTo(before.decisions)
    }

    @Test
    fun `start replay refuses legacy run without frozen flow metadata before enqueue and preserves decisions`() {
        workflowRepository.saveDefinition(scope, definition("1.0.0", "hash-v1"))
        val before = persistedRun("legacy", flowVersions = emptyMap(), hashes = emptyMap())
        val submitter = mockk<SessionRunSubmissionService>(relaxed = true)

        assertThatThrownBy { replay(before.runId, selector("1.0.0"), submitter) }
            .hasMessageContaining("no frozen flow catalog")
        verify(exactly = 0) { submitter.submit(any(), any(), any(), any(), any(), any()) }
        assertThat(runRepository.find(scope, namespace, before.runId)!!.decisions).isEqualTo(before.decisions)
    }

    @Test
    fun `start replay keeps pinned definition when newer catalog version is available`() {
        workflowRepository.saveDefinition(scope, definition("1.0.0", "hash-v1"))
        workflowRepository.saveDefinition(scope, definition("2.0.0", "hash-v2"))
        val before = persistedRun("newer-available")
        val submitter = mockk<SessionRunSubmissionService>(relaxed = true)

        val replayed = replay(before.runId, selector("1.0.0"), submitter)

        verify(exactly = 1) { submitter.submit(any(), namespace, "${before.runId}__f1", any(), "start-replay", any()) }
        assertThat(replayed.flowVersions).containsEntry(flowType, "1.0.0")
        assertThat(replayed.flowDefinitionHashes).containsEntry(flowType, "hash-v1")
        assertThat(replayed.decisions).isEqualTo(before.decisions)
    }
}
