package io.whozoss.factory.workspace

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.run.service.RunProvisioningTiming
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path
import java.time.Instant

class StandaloneWorkspaceProvisioningIntegrationTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var environments: WorkEnvironmentRepository
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var workflows: io.whozoss.factory.workflow.service.WorkflowService
    @Autowired lateinit var workflowRepository: io.whozoss.factory.workflow.persistence.WorkflowRepository
    @Autowired lateinit var evidence: io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
    @Autowired lateinit var interactions: io.whozoss.factory.workflow.persistence.HumanInteractionRepository
    @Autowired lateinit var sse: io.whozoss.factory.workflow.sse.WorkflowSseHub
    @Autowired lateinit var drain: io.whozoss.factory.agentattempt.service.OutboxDrainService
    @Autowired lateinit var outbox: io.whozoss.factory.agentattempt.persistence.SpringDataNeo4jOutboxRepository
    @TempDir lateinit var root: Path

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) { output }
        return output.trim()
    }

    /** Real durable outbox + real sequencer preclaim + real adoption; only post-claim execution is stubbed. */
    private fun drainFixture(synchronousFirst: Boolean = false, afterCommit: Boolean = false, status: () -> String): Pair<() -> Unit, () -> Unit> {
        git("init", "-b", "main")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--allow-empty", "-m", "initial")
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.ensureRunRootCase("case", "ns", "Title", "alice") } returns Unit
        every { proxy.fetchWorkspaceChanges("case", "alice") } answers {
            when (status()) {
                "ABSENT" -> null
                "RATE_LIMITED" -> throw io.whozoss.factory.proxy.AgentOsUnavailableException("HTTP",
                    org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS))
                else -> mapOf("equipped" to true, "status" to status(), "path" to root.toString())
            }
        }
        // Fake monotonic time advances through the REAL timeout check, without sleeping.
        var nanos = 0L
        val timing = object : RunProvisioningTiming(60_000, 30_000) {
            override fun nanoTime() = nanos
            override fun pause(milliseconds: Long) { nanos += java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(milliseconds) }
        }
        val raw = mapOf<String, Any?>("schemaVersion" to "1", "workflowType" to "adoption-drain", "version" to "1.0.0",
            "title" to "Title", "steps" to listOf(
                mapOf("id" to "agent", "name" to "Agent", "responsibility" to mapOf("kind" to "agent", "name" to "runner"), "dependsOn" to emptyList<String>()),
                mapOf("id" to "code", "name" to "Code", "responsibility" to mapOf("kind" to "code", "name" to "verify"), "dependsOn" to listOf("agent"))))
        val valid = io.whozoss.factory.workflow.domain.WorkflowDefinitionValidator.validate(raw) as io.whozoss.factory.workflow.domain.WorkflowDefinitionValidation.Valid
        workflows.registerDefinition(scope, io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord(
            workflowType = "adoption-drain", version = "1.0.0", definitionHash = io.whozoss.factory.workflow.domain.hashWorkflowDefinition(valid.definition), definition = valid.definition))
        workflows.start(scope, "ns", io.whozoss.factory.workflow.domain.WorkflowStartCommand("workflow", "adoption-drain", "Title"),
            io.whozoss.factory.workflow.domain.ControllerExecutionInput("test", "agentos", "runner", namespaceId = "ns"))
        val capabilities = mockk<io.whozoss.factory.capability.CapabilityExecutionService>(relaxed = true)
        // No user Stop here: a relaxed mock would otherwise answer a non-null attempt id and seal the step.
        every { capabilities.attemptsOf(any(), any(), any()) } returns emptyList()
        every { capabilities.userCancelledAttemptId(any(), any(), any(), any(), any()) } returns null
        val parent = mockk<io.whozoss.factory.run.service.RunService>(relaxed = true)
        var notifications = 0
        every { parent.onFragmentSessionUpdated(any(), any(), any(), any(), any(), any()) } answers {
            notifications++
            null
        }
        val executions = mutableSetOf<String>()
        every { capabilities.resolveAndRecord(any(), any(), any(), any(), any(), any(), any()) } answers {
            check(executions.add(arg<io.whozoss.factory.workflow.domain.WorkflowStepDefinition>(3).id)) { "Duplicate external execution" }
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse()
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()).isFalse()
            assertThat(arg<Path>(4)).isEqualTo(root)
            assertThat(arg<String>(5)).isEqualTo("#1684")
            io.whozoss.factory.capability.CapabilityExecution(
                io.whozoss.factory.capability.CapabilityOutcome.AgentCompleted(arg<io.whozoss.factory.workflow.domain.WorkflowStepDefinition>(3).id, "runner", "PASS"))
        }
        val event = io.whozoss.factory.agentattempt.persistence.OutboxEventNode(
            id = io.whozoss.factory.agentattempt.persistence.OutboxEventNode.compositeId(scope.organizationId, "source"),
            organizationId = scope.organizationId, eventId = "source", workstreamId = scope.workstreamId,
            eventType = "session_run_requested", status = "pending",
            payload = com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(mapOf("namespaceId" to "ns", "workflowId" to "workflow", "repoRoot" to root.toString(), "ticket" to "#1684")))
        outbox.deleteAll(outbox.findAllByOrganization(scope.organizationId).filter { it.payload.contains("workflow") })
        if (!synchronousFirst) outbox.save(event)
        var firstCall = true
        var now = Instant.now()
        val clock = object : java.time.Clock() {
            override fun getZone() = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
            override fun instant() = now
        }
        val runDrain: () -> Unit = {
            // Reconstruct all in-memory orchestrators each pass: only database intent survives.
            val provisioning = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, timing, WorkspaceProperties(), singleProbe = false)
            every { capabilities.prepareAgentWorkspace(any(), any(), any(), any()) } answers {
                provisioning.ensureReady(scope, "ns", "workflow", "case", "Title", "alice")
            }
            val session = io.whozoss.factory.workflow.service.SessionRunService(workflowRepository, evidence, interactions, capabilities, sse,
                transactionManager = transactions, outboxDrainService = drain, runService = parent)
            if (synchronousFirst && firstCall) {
                firstCall = false
                val resume = {
                    assertThatThrownBy { session.runSession(scope, "ns", "workflow", root, "#1684") }
                        .isInstanceOf(TransientWorkspaceAdoptionException::class.java)
                    Unit
                }
                if (afterCommit) {
                    // afterCommit still has the committed Neo4j resource bound to this thread.
                    // Dispatch into an independent execution context, as the durable worker does;
                    // NOT_SUPPORTED cannot restore a suspended Neo4j resource (SDN expects a
                    // non-null transaction object on resume). Join only AFTER caller cleanup.
                    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                    var resumed: java.util.concurrent.Future<*>? = null
                    try {
                        org.springframework.transaction.support.TransactionTemplate(transactions).execute {
                            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                                object : org.springframework.transaction.support.TransactionSynchronization {
                                    override fun afterCommit() {
                                        resumed = executor.submit { resume() }
                                    }
                                })
                        }
                        checkNotNull(resumed).get(30, java.util.concurrent.TimeUnit.SECONDS)
                    } finally {
                        executor.shutdownNow()
                    }
                } else resume()
            } else {
                val previousNotifications = notifications
                io.whozoss.factory.agentattempt.service.OutboxDrainWorker(drain, session, proxy, com.fasterxml.jackson.databind.ObjectMapper(), clock,
                    continuationExecutor = io.whozoss.factory.agentattempt.service.ContinuationExecutor { it.run() }).drain()
                if (status() == "READY" && workflowRepository.findInstance(scope, "ns", "workflow")!!.status == "active" &&
                    outbox.findAllByOrganization(scope.organizationId).any { it.eventType == "session_continuation" && it.status == "dispatched" }) {
                    assertThat(notifications).isGreaterThan(previousNotifications)
                }
            }
        }
        return runDrain to { now = now.plusSeconds(10) }
    }

    @Test fun `HTTP 429 read is deferred durably then resumes without terminal adoption marker`() {
        var status = "RATE_LIMITED"
        val (run, advance) = drainFixture { status }
        run()
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")!!.provisioningFailure).isNull()
        assertThat(outbox.findAllByOrganization(scope.organizationId).single { it.eventType == "session_continuation" }.status)
            .isEqualTo("pending")
        status = "READY"
        advance()
        run()
        assertThat(workflowRepository.findStepStates(scope, "ns", "workflow").first { it.stepId == "agent" }.status).isEqualTo("completed")
    }

    @Test fun `missing workspace read retains intent and resumes through durable drain`() {
        var status = "ABSENT"
        val (run, advance) = drainFixture { status }
        run()
        val intent = environments.findLatestByWorkflowId(scope, "workflow")!!
        assertThat(intent.provisioningFailure).isNull()
        val event = outbox.findAllByOrganization(scope.organizationId).single { it.eventType == "session_continuation" }
        assertThat(event.retryDeadline).isNotNull()
        assertThat(event.diagnostic).isEqualTo("WORKSPACE_ADOPTION_DEFERRED")
        status = "READY"
        advance()
        run()
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")!!.lifecycleState).isEqualTo(WorkEnvironmentState.READY)
    }

    @Test fun `synchronous run boundary retains a single durable wakeup then drain resumes`() {
        var status = "PREPARING"
        val (run, advance) = drainFixture(synchronousFirst = true) { status }
        run()
        val events = outbox.findAllByOrganization(scope.organizationId)
        assertThat(events.filter { it.eventType == "session_continuation" }).hasSize(1)
        val wakeup = events.single { it.eventType == "session_continuation" }
        assertThat(wakeup.retryDeadline).isNotNull()
        assertThat(wakeup.payload).contains(root.toString(), "#1684", "ns", "workflow")
        status = "READY"
        advance()
        run()
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")!!.lifecycleState).isEqualTo(WorkEnvironmentState.READY)
        assertThat(outbox.findAllByOrganization(scope.organizationId).single { it.eventType == "session_continuation" }.status).isEqualTo("dispatched")
    }

    @Test fun `continuation on an independent thread after commit retains durable adoption wait`() {
        var status = "PREPARING"
        val (run, advance) = drainFixture(synchronousFirst = true, afterCommit = true) { status }
        run()
        val wakeup = outbox.findAllByOrganization(scope.organizationId).filter { it.eventType == "session_continuation" }
        assertThat(wakeup).hasSize(1)
        assertThat(wakeup.single().status).isEqualTo("pending")
        assertThat(wakeup.single().retryDeadline).isNotNull()
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")!!.lifecycleState).isEqualTo(WorkEnvironmentState.PROVISIONING)
        assertThat(workflowRepository.findStepStates(scope, "ns", "workflow").first { it.stepId == "agent" }.status).isEqualTo("ready")
        status = "READY"
        advance()
        run()
        assertThat(workflowRepository.findStepStates(scope, "ns", "workflow").first { it.stepId == "agent" }.status).isEqualTo("completed")
        assertThat(outbox.findAllByOrganization(scope.organizationId).single { it.eventType == "session_continuation" }.status).isEqualTo("dispatched")
    }

    @Test fun `failed intermediate projection does not prevent remaining ready step or fragment notification`() {
        val (run, _) = drainFixture { "READY" }
        val instance = workflowRepository.findInstance(scope, "ns", "workflow")!!
        workflowRepository.updateInstance(scope, "ns", "workflow", instance.revision,
            instance.copy(revision = instance.revision + 1, projection = instance.projection + ("status" to "failed")))
        run()
        assertThat(workflowRepository.findStepStates(scope, "ns", "workflow").first { it.stepId == "agent" }.status).isEqualTo("completed")
    }

    @Test fun `timeout resumes via durable drain after restart with backoff and no duplicate turn`() {
        var status = "PREPARING"
        val (runDrain, advance) = drainFixture { status }
        runDrain()
        val intent = environments.findLatestByWorkflowId(scope, "workflow")!!
        assertThat(intent.lifecycleState).isEqualTo(WorkEnvironmentState.PROVISIONING)
        assertThat(workflowRepository.findStepStates(scope, "ns", "workflow").first { it.stepId == "agent" }.status).isEqualTo("ready")
        fun continuation() = outbox.findAllByOrganization(scope.organizationId).single { it.eventType == "session_continuation" }
        assertThat(continuation().attempts).isEqualTo(1)
        assertThat(continuation().diagnostic).isEqualTo("WORKSPACE_ADOPTION_DEFERRED")
        status = "READY"
        runDrain() // Not due, even though AgentOS is now ready.
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")).isEqualTo(intent)
        assertThat(continuation().attempts).isEqualTo(1)
        advance()
        runDrain()
        val adopted = environments.findLatestByWorkflowId(scope, "workflow")!!
        assertThat(adopted.lifecycleState).isEqualTo(WorkEnvironmentState.READY)
        assertThat(adopted.environmentId).isEqualTo(intent.environmentId)
        assertThat(continuation().status).isEqualTo("dispatched")
        // Simulate death after a successful turn and before the outbox acknowledgement.
        outbox.save(continuation().copy(status = "pending"))
        runDrain()
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")).isEqualTo(adopted)
        assertThat(evidence.list(scope, "ns", "workflow").count { it.kind == "session-step" && it.stepId == "agent" && it.outcome == "completed" }).isEqualTo(1)
    }

    @Test fun `replayed continuation never redispatches an uncertain running agent`() {
        var status = "PREPARING"
        val (runDrain, advance) = drainFixture { status }
        runDrain()
        // An accepted message may exist even if Factory lost its observation.
        workflowRepository.upsertStepState(scope, io.whozoss.factory.workflow.domain.WorkflowStepStateRecord(
            "ns", "workflow", "agent", 1, "running", emptyMap()))
        status = "READY"
        advance()
        runDrain()
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")!!.lifecycleState).isEqualTo(WorkEnvironmentState.PROVISIONING)
        assertThat(workflowRepository.findStepStates(scope, "ns", "workflow").first { it.stepId == "agent" }.status).isEqualTo("running")
        assertThat(evidence.list(scope, "ns", "workflow").none { it.kind == "session-step" && it.outcome == "completed" }).isTrue()
    }

    @Test fun `removed workflow is never reactivated by deferred continuation`() {
        var status = "PREPARING"
        val (runDrain, advance) = drainFixture { status }
        runDrain()
        val instance = workflowRepository.findInstance(scope, "ns", "workflow")!!
        assertThat(workflowRepository.updateInstance(scope, "ns", "workflow", instance.revision,
            instance.copy(status = "removed", revision = instance.revision + 1))).isTrue()
        status = "READY"
        advance()
        runDrain()
        assertThat(workflowRepository.findInstance(scope, "ns", "workflow")!!.status).isEqualTo("removed")
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")!!.lifecycleState).isEqualTo(WorkEnvironmentState.PROVISIONING)
        assertThat(outbox.findAllByOrganization(scope.organizationId).single { it.eventType == "session_continuation" }.status).isEqualTo("dead")
    }

    @Test fun `FAILED adoption is terminal through drain even if AgentOS later becomes READY`() {
        var status = "FAILED"
        val (runDrain, advance) = drainFixture { status }
        runDrain()
        val failed = environments.findLatestByWorkflowId(scope, "workflow")!!
        assertThat(failed.provisioningFailure).isEqualTo("ROOT_WORKSPACE_ADOPTION_FAILED")
        assertThat(workflowRepository.findStepStates(scope, "ns", "workflow").first { it.stepId == "agent" }.status).isEqualTo("failed")
        status = "READY"
        advance()
        val continuation = outbox.findAllByOrganization(scope.organizationId).single { it.eventType == "session_continuation" }
        outbox.save(continuation.copy(status = "pending"))
        runDrain()
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")).isEqualTo(failed)
    }

    @Test fun `asynchronous adoption waits then READY replay preserves original baseline and revision`() {
        git("init", "-b", "main")
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--allow-empty", "-m", "initial")
        val head = git("rev-parse", "HEAD")
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.ensureRunRootCase("case", "ns", "Title", "alice") } returns Unit
        var reads = 0
        every { proxy.fetchWorkspaceChanges("case", "alice") } answers {
            if (reads++ == 0) mapOf("equipped" to true, "status" to "PREPARING")
            else mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        }
        val timing = object : RunProvisioningTiming(1000, 1) {
            override fun nanoTime() = 0L
            override fun pause(milliseconds: Long) = Unit
        }
        val service = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, timing, WorkspaceProperties(), singleProbe = false)
        service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice")
        val adopted = environments.findLatestByWorkflowId(scope, "workflow")!!
        assertThat(adopted.baseCommit).isEqualTo(head)
        assertThat(reads).isEqualTo(2)
        git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "--allow-empty", "-m", "agent work")
        service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice")
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")).isEqualTo(adopted)
        assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "different-case", "Title", "alice") }
            .hasMessageContaining("identity mismatch")
        assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "bob") }
            .hasMessageContaining("identity mismatch")
        val busy = environments.updateState(scope, adopted.copy(lifecycleState = WorkEnvironmentState.BUSY), adopted.revision, Instant.now())
        assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice") }
            .hasMessageContaining("busy or decommissioned")
        val gone = environments.updateState(scope, busy.copy(lifecycleState = WorkEnvironmentState.DECOMMISSIONED), busy.revision, Instant.now())
        assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice") }
            .hasMessageContaining("busy or decommissioned")
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")).isEqualTo(gone)
        verify(exactly = 2) { proxy.ensureRunRootCase(any(), any(), any(), any()) }
    }

    @Test fun `existing foreign environment is never overwritten and missing actor never calls AgentOS`() {
        val foreign = environments.insert(scope, WorkEnvironment(scope.organizationId, scope.workstreamId,
            "foreign", "workflow", "workflow", "ns", "wrong-root", root.toString(), "main", "main", root.toString(),
            lifecycleState = WorkEnvironmentState.READY, createdBy = "alice"))
        val proxy = mockk<AgentOsProxyClient>()
        val service = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, RunProvisioningTiming(0, 1), WorkspaceProperties())
        assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice") }
            .hasMessageContaining("identity mismatch")
        assertThatThrownBy { service.ensureReady(scope, "ns", "new-workflow", "case", "Title", null) }
            .hasMessageContaining("Authenticated")
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")).isEqualTo(foreign)
        assertThat(environments.findLatestByWorkflowId(scope, "new-workflow")).isNull()
        verify(exactly = 0) { proxy.ensureRunRootCase(any(), any(), any(), any()) }
    }

    @Test fun `negative ancestry is terminal but Git inspection failure is transient`() {
        val reserved = environments.insert(scope, WorkEnvironment(scope.organizationId, scope.workstreamId,
            "intent", "wf", "wf", "ns", "case", "", "", null, "", createdBy = "alice"))
        java.nio.file.Files.createFile(root.resolve(".git"))
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.fetchWorkspaceChanges("case", "alice") } returns mapOf("equipped" to true,
            "status" to "READY", "path" to root.toString(), "changes" to mapOf("base" to "b".repeat(40)))
        var ancestryExit = 1
        val runner = io.whozoss.factory.environment.port.GitCommandRunner { _, args ->
            io.whozoss.factory.environment.port.GitCommandResult(
                if (args.first() == "merge-base") ancestryExit else 0,
                if ("--show-toplevel" in args) root.toString() else "a".repeat(40), "")
        }
        val adoption = RootCaseWorkspaceAdoption(environments, proxy, transactions, RunProvisioningTiming(0, 1), runner)
        assertThatThrownBy { adoption.awaitReady(scope, reserved, "alice", inspectGit = true) }
            .isInstanceOf(TerminalWorkspaceAdoptionException::class.java).hasMessageContaining("does not precede")
        ancestryExit = 128
        assertThatThrownBy { adoption.awaitReady(scope, reserved, "alice", inspectGit = true) }
            .isNotInstanceOf(TerminalWorkspaceAdoptionException::class.java).hasMessageContaining("inspection unavailable")
        assertThat(environments.findByEnvironmentId(scope, "intent")).isEqualTo(reserved)
    }

    @Test fun `changed environment owner during adoption is terminal and foreign record remains untouched`() {
        val reserved = environments.insert(scope, WorkEnvironment(scope.organizationId, scope.workstreamId,
            "intent", "wf", "wf", "ns", "case", "", "", null, "", createdBy = "alice"))
        val foreign = environments.updateState(scope, reserved.copy(parentCaseId = "foreign-root"), reserved.revision, Instant.now())
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.fetchWorkspaceChanges("case", "alice") } returns mapOf("equipped" to true,
            "status" to "READY", "path" to root.toString())
        assertThatThrownBy {
            RootCaseWorkspaceAdoption(environments, proxy, transactions, RunProvisioningTiming(0, 1))
                .awaitReady(scope, reserved, "alice")
        }.isInstanceOf(TerminalWorkspaceAdoptionException::class.java).hasMessageContaining("identity mismatch")
        assertThat(environments.findByEnvironmentId(scope, "intent")).isEqualTo(foreign)
    }

    @Test fun `different physical root on READY replay is a terminal invariant without replacing environment`() {
        val adopted = environments.insert(scope, WorkEnvironment(scope.organizationId, scope.workstreamId,
            "intent", "wf", "wf", "ns", "case", root.toString(), "", null, root.toString(),
            lifecycleState = WorkEnvironmentState.READY, createdBy = "alice"))
        val other = java.nio.file.Files.createDirectory(root.resolve("other"))
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.fetchWorkspaceChanges("case", "alice") } returns mapOf("equipped" to true,
            "status" to "READY", "path" to other.toString())
        assertThatThrownBy {
            RootCaseWorkspaceAdoption(environments, proxy, transactions, RunProvisioningTiming(0, 1))
                .awaitReady(scope, adopted, "alice")
        }.isInstanceOf(TerminalWorkspaceAdoptionException::class.java).hasMessageContaining("physical identity")
        assertThat(environments.findByEnvironmentId(scope, "intent")).isEqualTo(adopted)
    }

    @Test fun `interrupted polling retains committed intent and interrupt flag`() {
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "PREPARING")
        val timing = object : RunProvisioningTiming(1000, 1) {
            override fun nanoTime() = 0L
            override fun pause(milliseconds: Long) { throw InterruptedException("poll interrupted") }
        }
        val service = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, timing, WorkspaceProperties(), singleProbe = false)
        try {
            assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice") }
                .isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally { Thread.interrupted() }
        val pending = environments.findLatestByWorkflowId(scope, "workflow")!!
        assertThat(pending.provisioningFailure).isNull()
        assertThat(pending.lifecycleState).isEqualTo(WorkEnvironmentState.PROVISIONING)
        assertThat(pending.revision).isEqualTo(1)
    }

    @Test fun `single standalone probe does not sleep and programming errors are not retried`() {
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "PREPARING")
        val timing = object : RunProvisioningTiming() {
            override fun pause(milliseconds: Long) { error("single probe must not sleep") }
        }
        val service = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, timing, WorkspaceProperties())
        assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice") }
            .isInstanceOf(TransientWorkspaceAdoptionException::class.java)
        every { proxy.fetchWorkspaceChanges(any(), any()) } throws IllegalArgumentException("programming defect")
        assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .isNotInstanceOf(TransientWorkspaceAdoptionException::class.java)
    }

    @Test fun `HTTP permanent refusal is persisted and wrapped 408 429 server and network errors are transient`() {
        val proxy = mockk<AgentOsProxyClient>()
        val service = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, RunProvisioningTiming(), WorkspaceProperties())
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } throws
            io.whozoss.factory.proxy.AgentOsUnavailableException("refused",
                org.springframework.web.client.HttpClientErrorException(org.springframework.http.HttpStatus.FORBIDDEN))
        assertThatThrownBy { service.ensureReady(scope, "ns", "forbidden", "case", "Title", "alice") }
            .isInstanceOf(TerminalWorkspaceAdoptionException::class.java)
        assertThat(environments.findLatestByWorkflowId(scope, "forbidden")!!.provisioningFailure)
            .isEqualTo("ROOT_WORKSPACE_ADOPTION_FAILED")
        listOf(org.springframework.http.HttpStatus.REQUEST_TIMEOUT, org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE).forEach { status ->
            every { proxy.ensureRunRootCase(any(), any(), any(), any()) } throws
                io.whozoss.factory.proxy.AgentOsUnavailableException("HTTP", org.springframework.web.client.RestClientResponseException(
                    "HTTP", status.value(), status.reasonPhrase, null, null, null))
            val id = "http-${status.value()}"
            assertThatThrownBy { service.ensureReady(scope, "ns", id, "case", "Title", "alice") }
                .isInstanceOf(TransientWorkspaceAdoptionException::class.java)
            assertThat(environments.findLatestByWorkflowId(scope, id)!!.provisioningFailure).isNull()
        }
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } throws
            io.whozoss.factory.proxy.AgentOsUnavailableException("network", java.io.IOException("connection reset"))
        assertThatThrownBy { service.ensureReady(scope, "ns", "network", "case", "Title", "alice") }
            .isInstanceOf(TransientWorkspaceAdoptionException::class.java)
    }

    @Test fun `wrapped HTTP interruption is propagated without becoming a transient adoption retry`() {
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } throws
            RuntimeException("HTTP failure", InterruptedException("shutdown"))
        val service = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, RunProvisioningTiming(0, 1), WorkspaceProperties())
        try {
            assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice") }
                .isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally { Thread.interrupted() }
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")!!.provisioningFailure).isNull()
    }

    @Test fun `failed intent refuses replay even when AgentOS later becomes ready`() {
        val proxy = mockk<AgentOsProxyClient>()
        every { proxy.ensureRunRootCase(any(), any(), any(), any()) } returns Unit
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "FAILED")
        val service = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, RunProvisioningTiming(0, 1), WorkspaceProperties())
        assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice") }
            .hasMessageContaining("preparation failed")
        val failed = environments.findLatestByWorkflowId(scope, "workflow")!!
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        assertThatThrownBy { service.ensureReady(scope, "ns", "workflow", "case", "Title", "alice") }
            .hasMessageContaining("previously failed")
        assertThat(environments.findLatestByWorkflowId(scope, "workflow")).isEqualTo(failed)
        assertThat(WorkEnvironmentWorkspaceStateAdapter(environments).latestForWorkflow(scope, "workflow")!!.status).isEqualTo(WorkspaceStatus.FAILED)
        verify(exactly = 1) { proxy.ensureRunRootCase(any(), any(), any(), any()) }
        verify(exactly = 1) { proxy.fetchWorkspaceChanges(any(), any()) }
    }
}
