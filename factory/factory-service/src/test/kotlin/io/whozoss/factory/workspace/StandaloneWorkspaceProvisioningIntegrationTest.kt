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
    @TempDir lateinit var root: Path

    private fun git(vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) { output }
        return output.trim()
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
        val service = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, timing, WorkspaceProperties())
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
        val service = StandaloneWorkspaceProvisioningService(environments, proxy, transactions, timing, WorkspaceProperties())
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
