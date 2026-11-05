package io.whozoss.factory.agentattempt

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.adapter.agentos.*
import io.whozoss.factory.agentattempt.domain.DurableAgentAttempt
import io.whozoss.factory.agentattempt.service.BridgeRecoveryWorker
import io.whozoss.factory.agentattempt.service.DurableAgentAttemptService
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.workflow.domain.WorkflowInstanceRecord
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.worktree.AgentOsWorkspaceIdentityValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class BridgeRecoveryWorkerWorkspacePreflightTest : Neo4jDomainIntegrationTest() {
    @Autowired lateinit var attempts: DurableAgentAttemptService
    @Autowired lateinit var workflows: WorkflowRepository
    @Autowired lateinit var environments: WorkEnvironmentRepository
    @Autowired lateinit var occupations: io.whozoss.factory.worktree.persistence.Neo4jWorktreeExecutionOccupancyRepository
    @Autowired lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager
    @TempDir lateinit var root: Path
    private val namespace="3f8a2c10-9e3b-4c5a-8a11-recoverycheck"

    @Test fun `missing workflow context creates and starts nothing`() {
        val adapter = adapter()
        attempts.register(scope,attempt("missing",null,null))
        val report=worker(adapter,readyProxy(root)).recover()
        assertThat(report.skipped).isEqualTo(1)
        verify(exactly = 1) { adapter.reconcileResult(any()) }
        verify(exactly=0) { adapter.createOrRecoverExecution(any<TrustedCaseBinding>(),any(),any()) }
        verify(exactly=0) { adapter.startTurn(any<TrustedCaseBinding>(),any(),any()) }
    }

    @Test fun `parent divergent workspace starts no turn`() {
        val fixture=parent("divergent")
        val other=Files.createDirectory(root.resolve("other"))
        val adapter=adapter()
        val report=worker(adapter,readyProxy(other)).recover()
        assertThat(report.skipped).isEqualTo(1)
        verify(exactly=0) { adapter.createOrRecoverExecution(any<TrustedCaseBinding>(),any(),any()) }
        verify(exactly=0) { adapter.startTurn(any<TrustedCaseBinding>(),any(),any()) }
    }

    @Test fun `parent matching workspace redrives exactly one turn`() {
        val original = parent("nominal")
        val adapter=adapter()
        val report=worker(adapter,readyProxy(root)).recover()
        assertThat(report.redriven).isEqualTo(1)
        val bound = attempts.find(scope, namespace, original.workflowId, original.stepId, original.attemptId)!!
        assertThat(bound.occupancyResourceIdentity).isNotNull()
        assertThat(bound.occupancyGeneration).isEqualTo(1L)
        assertThat(occupations.find(bound.occupancyResourceIdentity!!)!!.status)
            .isEqualTo(io.whozoss.factory.worktree.domain.WorktreeExecutionOccupancyStatus.ACTIVE)
        verify(exactly=1) { adapter.startTurn(any<TrustedCaseBinding>(),any(),any()) }
    }

    @Test fun `recovery validates root then same child identity before starting and refuses child divergence`() {
        val original = parent("ordered")
        val adapter = adapter()
        val proxy = readyProxy(root)
        val calls = mutableListOf<String>()
        every { proxy.fetchWorkspaceChanges(any(), any()) } answers {
            val id = firstArg<String>()
            calls.add(if (id == original.rootCaseId) "root" else "child")
            mapOf("equipped" to true, "status" to "READY", "path" to root.toString())
        }
        every { adapter.createOrRecoverExecution(any<TrustedCaseBinding>(), any(), any()) } answers {
            calls.add("create")
            val binding = firstArg<TrustedCaseBinding>()
            CaseHandle(binding.caseId, binding.namespaceId, false)
        }
        every { adapter.startTurn(any<TrustedCaseBinding>(), any(), any()) } answers {
            calls.add("turn")
            TurnToken.unbaselined(original.caseId, original.attemptId)
        }
        worker(adapter, proxy).recover()
        assertThat(calls).containsExactly("root", "create", "child", "turn")

        val other = parent("child-divergent")
        val otherAdapter = adapter()
        val otherProxy = readyProxy(root)
        every { otherProxy.fetchWorkspaceChanges(other.caseId, any()) } returns mapOf(
            "equipped" to true, "status" to "READY", "path" to root.resolve("missing").toString())
        worker(otherAdapter, otherProxy).recover()
        verify(exactly = 0) { otherAdapter.startTurn(any<TrustedCaseBinding>(), any(), any()) }
    }

    private fun parent(id:String):DurableAgentAttempt {
        Files.createDirectories(root)
        val workflow="wf-$id"; val run="run-$id"; val rootCase=UUID.randomUUID().toString(); val environment="env-$id"
        workflows.insertInstance(scope,WorkflowInstanceRecord(namespace,workflow,1,"active","hash-$id",mapOf("runId" to run),emptyMap(),rootCaseId=rootCase))
        environments.insert(scope,WorkEnvironment(scope.organizationId,scope.workstreamId,environment,"wu-$id",run,namespace,rootCase,root.toString(),"main","branch",root.toString(),lifecycleState=WorkEnvironmentState.READY,createdBy="test"))
        return attempt(id,environment,rootCase).also { attempts.register(scope,it) }
    }

    private fun attempt(id:String,environment:String?,rootCase:String?)=DurableAgentAttempt(
        attemptId="attempt-$id",caseId=UUID.randomUUID().toString(),namespaceId=namespace,workflowId="wf-$id",stepId="step",attemptNumber=1,agentName="Writer",brief="work",environmentRef=environment,expectedEnvironmentRevision=environment?.let { 1 },rootCaseId=rootCase,parentCaseId=rootCase,
    )

    private fun adapter():AgentOsExecutionAdapter {
        val adapter=mockk<AgentOsExecutionAdapter>(relaxed=true)
        every { adapter.createOrRecoverExecution(any<TrustedCaseBinding>(),any(),any()) } answers { firstArg<TrustedCaseBinding>().let { CaseHandle(it.caseId,it.namespaceId,false) } }
        every { adapter.reconcileResult(any()) } returns AgentOsObservationResult(
            AgentOsExecutionVerdict.Indeterminate("not started"),
            null,
        )
        every { adapter.observeTurnResult(any(), any(), any(), any()) } returns AgentOsObservationResult(
            AgentOsExecutionVerdict.Succeeded(emptyMap()),
            null,
        )
        return adapter
    }

    private fun readyProxy(path:Path):AgentOsProxyClient = mockk<AgentOsProxyClient>().also { proxy -> every { proxy.fetchWorkspaceChanges(any(),any()) } returns mapOf("equipped" to true,"status" to "READY","path" to path.toString()) }

    private fun worker(adapter:AgentOsExecutionAdapter,proxy:AgentOsProxyClient)=BridgeRecoveryWorker(attempts,adapter,workflowRepository=workflows,workEnvironmentRepository=environments,workspaceIdentityValidator=AgentOsWorkspaceIdentityValidator(proxy),leaseTtlMs=0,worktreeOccupancyService=io.whozoss.factory.worktree.WorktreeExecutionOccupancyService(environments, occupations, transactionManager=transactionManager))
}
