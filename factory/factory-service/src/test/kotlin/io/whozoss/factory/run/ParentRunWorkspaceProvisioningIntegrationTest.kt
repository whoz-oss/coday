package io.whozoss.factory.run

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.run.domain.RunRecord
import io.whozoss.factory.run.persistence.RunRepository
import io.whozoss.factory.run.service.ParentRunWorkspaceProvisioningService
import io.whozoss.factory.run.persistence.RunProvisioningIntentRepository
import io.whozoss.factory.run.service.RunStartCommand
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.file.Path

class ParentRunWorkspaceProvisioningIntegrationTest : Neo4jDomainIntegrationTest() {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    lateinit var fakeProvisioner: io.whozoss.factory.environment.port.FakeGitProvisioner
    @Autowired lateinit var runs: RunRepository
    @Autowired lateinit var environments: WorkEnvironmentRepository
    @Autowired lateinit var intents: RunProvisioningIntentRepository
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var workflows: io.whozoss.factory.workflow.persistence.WorkflowRepository
    @Autowired lateinit var evidence: io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
    @Autowired lateinit var interactions: io.whozoss.factory.workflow.persistence.HumanInteractionRepository
    @Autowired lateinit var outbox: io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
    @Autowired lateinit var submitter: io.whozoss.factory.workflow.service.SessionRunSubmissionService
    @TempDir lateinit var root: Path

    @Test
    fun `startRun resumes failed provisioning before single decision fragment and durable outbox`() {
        val proxy = mockk<AgentOsProxyClient>()
        val command = RunStartCommand("vertical-provision", "test", "Vertical")
        val selector = object : io.whozoss.factory.sdk.spi.FactoryFlowSelector {
            override fun runTypes() = setOf("test")
            override fun selectorVersion() = "1"
            override fun start(run: io.whozoss.factory.sdk.spi.RunView) = io.whozoss.factory.sdk.spi.RunUpdate() to
                io.whozoss.factory.sdk.spi.FlowDecision.Apply(flowType = "test.provision", reason = "start")
            override fun next(run: io.whozoss.factory.sdk.spi.RunView) = io.whozoss.factory.sdk.spi.FlowDecision.Complete("done")
        }
        workflows.saveDefinition(scope, io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord(
            "test.provision", "1", "hash", mapOf("steps" to listOf(mapOf("id" to "gate", "name" to "Gate",
                "responsibility" to mapOf("kind" to "human"), "dependsOn" to emptyList<String>()))),
        ))
        val provisioning = ParentRunWorkspaceProvisioningService(runs, environments, intents, proxy, mapper, transactions)
        var crashDecision = true
        val decisionRepository = object : io.whozoss.factory.workflow.persistence.WorkflowRepository by workflows {
            override fun insertInstance(scope: io.whozoss.factory.persistence.TenantScope,
                record: io.whozoss.factory.workflow.domain.WorkflowInstanceRecord): io.whozoss.factory.workflow.domain.WorkflowInstanceRecord {
                val saved = workflows.insertInstance(scope, record)
                if (crashDecision) { crashDecision = false; error("crash before first decision commit") }
                return saved
            }
        }
        val service = io.whozoss.factory.run.service.RunService(runs, decisionRepository, evidence, interactions,
            io.whozoss.factory.workflow.service.SessionDefinitionCatalog(mapper, mockk<org.pf4j.PluginManager>(relaxed = true)),
            sessionRunSubmissionService = submitter, registeredSelectors = listOf(selector), transactionManager = transactions,
            workspaceProvisioning = provisioning)
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(runs.find(scope, "ns", command.runId)!!.decisions).isEmpty()
            assertThat(workflows.listInstances(scope, "ns")).isEmpty()
            assertThat(outbox.count()).isZero()
        }
        every { proxy.fetchWorkspaceChanges(any(), any()) } throws IllegalStateException("remote unavailable")
        assertThatThrownBy { service.startRun(scope, "ns", command, root.toString(), "alice") }.hasMessageContaining("remote unavailable")
        val intent = runs.find(scope, "ns", command.runId)!!
        val environment = environments.findLatestByWorkflowId(scope, command.runId)!!
        every { proxy.fetchWorkspaceChanges(any(), any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(workflows.listInstances(scope, "ns")).isEmpty()
            assertThat(outbox.count()).isZero()
            mapOf("equipped" to true, "status" to "READY", "path" to root.toString(),
                "changes" to mapOf("base" to "a".repeat(40)))
        }
        org.mockito.Mockito.clearInvocations(fakeProvisioner)
        assertThatThrownBy { service.startRun(scope, "ns", command, root.toString(), "alice") }
            .hasMessageContaining("before first decision commit")
        val readyBeforeDecision = environments.findLatestByWorkflowId(scope, command.runId)!!
        assertThat(readyBeforeDecision.lifecycleState).isEqualTo(WorkEnvironmentState.READY)
        assertThat(runs.find(scope, "ns", command.runId)!!.decisions).isEmpty()
        assertThat(workflows.listInstances(scope, "ns")).isEmpty()
        assertThat(outbox.count()).isZero()
        val started = service.startRun(scope, "ns", command, root.toString(), "alice")
        assertThat(started.rootCaseId).isEqualTo(intent.rootCaseId)
        assertThat(started.decisions).hasSize(1)
        assertThat(started.fragments).hasSize(1)
        assertThat(outbox.count()).isEqualTo(1L)
        val ready = environments.findLatestByWorkflowId(scope, command.runId)!!
        assertThat(ready.environmentId).isEqualTo(environment.environmentId)
        assertThat(ready.revision).isEqualTo(readyBeforeDecision.revision)
        assertThat(ready.baseCommit).isEqualTo("a".repeat(40))
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        val replay = service.startRun(scope, "ns", command, root.toString(), "alice")
        assertThat(replay.decisions).isEqualTo(started.decisions)
        assertThat(workflows.listInstances(scope, "ns")).hasSize(1)
        assertThat(environments.findLatestByWorkflowId(scope, command.runId)!!.revision).isEqualTo(ready.revision)
        org.mockito.Mockito.verifyNoInteractions(fakeProvisioner)
    }

    @Test
    fun `intent commits before HTTP and READY replay preserves environment revision`() {
        val proxy = mockk<AgentOsProxyClient>()
        val command = RunStartCommand("run-provision", "test", "Title", initialDomainState = linkedMapOf("b" to 2, "a" to linkedMapOf("z" to 3, "x" to 1)))
        val run = RunRecord("ns", command.runId, command.runType, "1", command.title, rootCaseId = java.util.UUID.randomUUID().toString())
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(runs.find(scope, "ns", command.runId)).isNotNull()
            assertThat(environments.findLatestByWorkflowId(scope, command.runId)!!.lifecycleState).isIn(WorkEnvironmentState.PROVISIONING, WorkEnvironmentState.READY)
            assertThat(runs.find(scope, "ns", command.runId)!!.fragments).isEmpty()
        }
        every { proxy.fetchWorkspaceChanges(any(), any()) } answers {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        }
        val service = ParentRunWorkspaceProvisioningService(runs, environments, intents, proxy, mapper, transactions)
        service.ensureReady(scope, run, command, null, "alice")
        val ready = environments.findLatestByWorkflowId(scope, command.runId)!!
        assertThat(ready.lifecycleState).isEqualTo(WorkEnvironmentState.READY)
        assertThat(ready.parentCaseId).isEqualTo(run.rootCaseId)
        assertThat(ready.branch).isNull()
        assertThat(ready.baseCommit).isNull()
        service.ensureReady(scope, run, command.copy(initialDomainState = linkedMapOf("a" to linkedMapOf("x" to 1, "z" to 3), "b" to 2)), null, "alice")
        assertThat(environments.findLatestByWorkflowId(scope, command.runId)!!.revision).isEqualTo(ready.revision)
        assertThatThrownBy { service.ensureReady(scope, run, command.copy(title = "different"), null, "alice") }
            .hasMessageContaining("identity")
        assertThatThrownBy { service.ensureReady(scope, run, command, null, "bob") }.hasMessageContaining("identity")
        verify(exactly = 2) { proxy.ensureRunRootCase(any(), any(), any(), any()) }
    }

    @Test
    fun `concurrent reservation never overwrites winning authenticated attribution`() {
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        val command = RunStartCommand("concurrent", "test", "Concurrent")
        val run = RunRecord("ns", command.runId, command.runType, "1", command.title, rootCaseId = java.util.UUID.randomUUID().toString())
        val barrier = java.util.concurrent.CyclicBarrier(2)
        // Force both real transactions to observe absence before either attempts insertion.
        val racingIntents = object : io.whozoss.factory.run.persistence.RunProvisioningIntentRepository by intents {
            override fun findById(id: String): java.util.Optional<io.whozoss.factory.run.persistence.RunProvisioningIntent> {
                val found = intents.findById(id)
                if (found.isEmpty) barrier.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return found
            }
        }
        val service = ParentRunWorkspaceProvisioningService(runs, environments, racingIntents, proxy, mapper, transactions)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("alice", "bob").map { actor ->
                pool.submit<Pair<String, Boolean>> { actor to runCatching { service.ensureReady(scope, run, command, null, actor) }.isSuccess }
            }
            val outcomes = futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(outcomes.count { it.second }).isEqualTo(1)
            val winner = outcomes.single { it.second }.first
            val key = mapper.writeValueAsString(listOf(scope.organizationId, scope.workstreamId, "ns", command.runId))
            val stored = intents.findById(key).orElseThrow()
            assertThat(stored.actorId).isEqualTo(winner)
            assertThat(intents.count()).isEqualTo(1L)
            val plainService = ParentRunWorkspaceProvisioningService(runs, environments, intents, proxy, mapper, transactions)
            assertThatThrownBy { plainService.ensureReady(scope, run, command, null, outcomes.single { !it.second }.first) }
                .hasMessageContaining("identity")
            assertThat(intents.findById(key).orElseThrow()).isEqualTo(stored)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `remote READY followed by local rollback replays same environment and READY local before decision is idempotent`() {
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        val command = RunStartCommand("ready-crash", "test", "Crash")
        val run = RunRecord("ns", command.runId, command.runType, "1", command.title, rootCaseId = java.util.UUID.randomUUID().toString())
        var fail = true
        val failingEnvironments = object : WorkEnvironmentRepository by environments {
            override fun updateState(scope: io.whozoss.factory.persistence.TenantScope, environment: io.whozoss.factory.environment.domain.WorkEnvironment,
                expectedRevision: Int, updatedAt: java.time.Instant): io.whozoss.factory.environment.domain.WorkEnvironment {
                val saved = environments.updateState(scope, environment, expectedRevision, updatedAt)
                if (fail) { fail = false; error("crash before READY commit") }
                return saved
            }
        }
        val service = ParentRunWorkspaceProvisioningService(runs, failingEnvironments, intents, proxy, mapper, transactions)
        assertThatThrownBy { service.ensureReady(scope, run, command, null, "alice") }.hasMessageContaining("before READY commit")
        val pending = environments.findLatestByWorkflowId(scope, command.runId)!!
        assertThat(pending.lifecycleState).isEqualTo(WorkEnvironmentState.PROVISIONING)
        service.ensureReady(scope, run, command, null, "alice")
        val ready = environments.findLatestByWorkflowId(scope, command.runId)!!
        assertThat(ready.environmentId).isEqualTo(pending.environmentId)
        assertThat(runs.find(scope, "ns", command.runId)!!.decisions).isEmpty()
        // Simulate restart after READY commit, before the caller commits its first decision.
        ParentRunWorkspaceProvisioningService(runs, environments, intents, proxy, mapper, transactions)
            .ensureReady(scope, run, command, null, "alice")
        assertThat(environments.findLatestByWorkflowId(scope, command.runId)).isEqualTo(ready)
        assertThat(intents.count()).isEqualTo(1L)
    }

    @Test
    fun `zero timeout retains intent and already interrupted entry preserves it without HTTP`() {
        val proxy = mockk<AgentOsProxyClient>()
        val command = RunStartCommand("run-timeout", "test", "Title")
        val run = RunRecord("ns", command.runId, command.runType, "1", command.title, rootCaseId = java.util.UUID.randomUUID().toString())
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "PREPARING")
        val service = ParentRunWorkspaceProvisioningService(runs, environments, intents, proxy, mapper, transactions,
            io.whozoss.factory.run.service.RunProvisioningTiming(timeoutMs = 0, pollIntervalMs = 1))
        assertThatThrownBy { service.ensureReady(scope, run, command, null, "alice") }.hasMessageContaining("timed out")
        val before = environments.findLatestByWorkflowId(scope, command.runId)!!
        val intentBefore = intents.findAll().single()
        assertThat(before.lifecycleState).isEqualTo(WorkEnvironmentState.PROVISIONING)
        try {
            Thread.currentThread().interrupt()
            assertThatThrownBy { service.ensureReady(scope, run, command, null, "alice") }.isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally { Thread.interrupted() }
        assertThat(environments.findLatestByWorkflowId(scope, command.runId)).isEqualTo(before)
        assertThat(intents.findAll().single()).isEqualTo(intentBefore)
        assertThat(runs.find(scope, "ns", command.runId)!!.fragments).isEmpty()
        verify(exactly = 1) { proxy.ensureRunRootCase(any(), any(), any(), any()) }
        verify(exactly = 1) { proxy.fetchWorkspaceChanges(any(), any()) }
    }

    @Test
    fun `already interrupted entry creates no intent and performs no HTTP`() {
        val proxy = mockk<AgentOsProxyClient>()
        val command = RunStartCommand("interrupted-entry", "test", "Title")
        val run = RunRecord("ns", command.runId, command.runType, "1", command.title, rootCaseId = java.util.UUID.randomUUID().toString())
        val service = ParentRunWorkspaceProvisioningService(runs, environments, intents, proxy, mapper, transactions)
        try {
            Thread.currentThread().interrupt()
            assertThatThrownBy { service.ensureReady(scope, run, command, null, "alice") }
                .isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally { Thread.interrupted() }
        assertThat(intents.count()).isZero()
        assertThat(runs.find(scope, "ns", command.runId)).isNull()
        assertThat(environments.findLatestByWorkflowId(scope, command.runId)).isNull()
        verify(exactly = 0) { proxy.ensureRunRootCase(any(), any(), any(), any()) }
        verify(exactly = 0) { proxy.fetchWorkspaceChanges(any(), any()) }
    }

    @Test
    fun `interruption during polling retains committed provisioning intent without READY or enqueue`() {
        val proxy = mockk<AgentOsProxyClient>()
        val command = RunStartCommand("interrupted-poll", "test", "Title")
        val run = RunRecord("ns", command.runId, command.runType, "1", command.title, rootCaseId = java.util.UUID.randomUUID().toString())
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "PREPARING")
        val timing = object : io.whozoss.factory.run.service.RunProvisioningTiming(timeoutMs = 60_000, pollIntervalMs = 1) {
            override fun nanoTime(): Long = 0L
            override fun pause(milliseconds: Long) {
                // Thread.sleep clears the flag on throwing; model that contract deterministically.
                throw InterruptedException("poll sleep interrupted")
            }
        }
        val service = ParentRunWorkspaceProvisioningService(runs, environments, intents, proxy, mapper, transactions, timing)
        try {
            assertThatThrownBy { service.ensureReady(scope, run, command, null, "alice") }
                .isInstanceOf(InterruptedException::class.java).hasMessageContaining("poll sleep interrupted")
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally { Thread.interrupted() }
        assertThat(intents.findAll().single().actorId).isEqualTo("alice")
        val retained = environments.findLatestByWorkflowId(scope, command.runId)!!
        assertThat(retained.lifecycleState).isEqualTo(WorkEnvironmentState.PROVISIONING)
        assertThat(retained.revision).isEqualTo(1)
        assertThat(retained.worktreePath).isEmpty()
        assertThat(runs.find(scope, "ns", command.runId)!!.decisions).isEmpty()
        assertThat(workflows.listInstances(scope, "ns")).isEmpty()
        assertThat(outbox.count()).isZero()
        verify(exactly = 1) { proxy.fetchWorkspaceChanges(any(), any()) }
    }

    @Test
    fun `remote failure leaves committed intent and replay adopts same root without READY seed`() {
        val proxy = mockk<AgentOsProxyClient>()
        val command = RunStartCommand("run-crash", "test", "Title")
        val run = RunRecord("ns", command.runId, command.runType, "1", command.title, rootCaseId = java.util.UUID.randomUUID().toString())
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } throws IllegalStateException("lost response")
        val service = ParentRunWorkspaceProvisioningService(runs, environments, intents, proxy, mapper, transactions)
        assertThatThrownBy { service.ensureReady(scope, run, command, null, "alice") }.hasMessageContaining("lost response")
        assertThat(environments.findLatestByWorkflowId(scope, command.runId)!!.lifecycleState).isEqualTo(WorkEnvironmentState.PROVISIONING)
        every { proxy.ensureRunRootCase(run.rootCaseId!!, "ns", "Title", "alice") } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "FAILED")
        assertThatThrownBy { service.ensureReady(scope, run, command, null, "alice") }.hasMessageContaining("preparation failed")
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "READY", "path" to root.resolve("missing").toString())
        assertThatThrownBy { service.ensureReady(scope, run, command, null, "alice") }
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        service.ensureReady(scope, run, command, null, "alice")
        assertThat(environments.findLatestByWorkflowId(scope, command.runId)!!.parentCaseId).isEqualTo(run.rootCaseId)
        assertThat(runs.find(scope, "ns", command.runId)!!.decisions).isEmpty()
    }
}
