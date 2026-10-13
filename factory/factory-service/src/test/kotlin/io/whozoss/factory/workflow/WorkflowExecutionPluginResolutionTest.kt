package io.whozoss.factory.workflow

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.sdk.spi.FactoryWorkflowExecutionPolicy
import io.whozoss.factory.workflow.domain.ControllerExecutionInput
import io.whozoss.factory.workflow.domain.WorkflowDefinitionRecord
import io.whozoss.factory.workflow.domain.WorkflowErrorCodes
import io.whozoss.factory.workflow.domain.WorkflowException
import io.whozoss.factory.workflow.domain.WorkflowExecutionPolicy
import io.whozoss.factory.workflow.domain.WorkflowStartCommand
import io.whozoss.factory.workflow.persistence.HumanInteractionRepository
import io.whozoss.factory.workflow.persistence.WorkflowEvidenceRepository
import io.whozoss.factory.workflow.persistence.WorkflowRepository
import io.whozoss.factory.workflow.service.SessionRunSubmissionService
import io.whozoss.factory.workflow.service.WorkflowHttpResult
import io.whozoss.factory.workflow.service.WorkflowService
import io.whozoss.factory.workflow.sse.WorkflowSseHub
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.pf4j.PluginManager
import org.pf4j.PluginState
import org.pf4j.PluginWrapper

/**
 * Unit tests of the PF4J execution-plugin resolution performed by
 * [WorkflowService.start] before any persistence or external effect (Factory
 * Forge — Lot A).
 *
 * A definition that declares a top-level `execution.plugin` can only start when
 * the declared plugin is present and started, resolved through the
 * [FactoryWorkflowExecutionPolicy] extensions (logical id) or the literal PF4J
 * plugin id. A definition without `execution` never consults the plugin manager.
 */
class WorkflowExecutionPluginResolutionTest {

    private val scope = TenantScope("org", "workstream")
    private val namespace = "00000000-0000-4000-8000-000000000001"

    private val repository = mockk<WorkflowRepository>(relaxed = true)
    private val evidenceRepository = mockk<WorkflowEvidenceRepository>(relaxed = true)
    private val interactionRepository = mockk<HumanInteractionRepository>(relaxed = true)
    private val sseHub = mockk<WorkflowSseHub>(relaxed = true)
    private val submission = mockk<SessionRunSubmissionService>()
    private val pluginManager = mockk<PluginManager>()

    private fun steps(): List<Map<String, Any?>> = listOf(
        linkedMapOf(
            "id" to "s1",
            "name" to "S1",
            "responsibility" to linkedMapOf("kind" to "agent", "name" to "a"),
            "dependsOn" to emptyList<String>(),
        ),
    )

    private fun definition(plugin: String? = "forge"): WorkflowDefinitionRecord {
        val base = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "workflowType" to "wf-exec",
            "version" to "1.0.0",
            "title" to "Exec",
            "steps" to steps(),
        )
        val definition = if (plugin == null) base else base + mapOf("execution" to mapOf("plugin" to plugin))
        return WorkflowDefinitionRecord(
            workflowType = "wf-exec",
            version = "1.0.0",
            definitionHash = "hash",
            definition = definition,
            executionPolicy = plugin?.let { WorkflowExecutionPolicy(it) },
        )
    }

    private fun service(): WorkflowService = WorkflowService(
        repository,
        evidenceRepository,
        interactionRepository,
        sseHub,
        sessionRunSubmissionService = submission,
        pluginManager = pluginManager,
    )

    private fun start(workflowId: String = "wf-1"): WorkflowHttpResult = service().start(
        scope,
        namespace,
        WorkflowStartCommand(workflowId = workflowId, workflowType = "wf-exec", title = "Exec"),
        ControllerExecutionInput(runtimeId = "r", kind = "agentos", agentId = "a"),
    )

    @Test
    fun `start is refused when the declared execution plugin is unknown`() {
        every { repository.listDefinitions(scope) } returns listOf(definition("forge"))
        every { pluginManager.getExtensions(FactoryWorkflowExecutionPolicy::class.java) } returns emptyList()
        every { pluginManager.getPlugin("forge") } returns null

        assertThatThrownBy { start() }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_EXECUTION_PLUGIN_NOT_FOUND)

        // The refusal happens before any persistence or external effect.
        verify(exactly = 0) { repository.insertInstance(any(), any()) }
        verify(exactly = 0) { submission.submit(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `start is refused when a literal plugin id exists but is not started`() {
        val wrapper = mockk<PluginWrapper>()
        every { wrapper.pluginState } returns PluginState.DISABLED
        every { repository.listDefinitions(scope) } returns listOf(definition("forge"))
        every { pluginManager.getExtensions(FactoryWorkflowExecutionPolicy::class.java) } returns emptyList()
        every { pluginManager.getPlugin("forge") } returns wrapper

        assertThatThrownBy { start() }
            .isInstanceOf(WorkflowException::class.java)
            .extracting("errorCode")
            .isEqualTo(WorkflowErrorCodes.WORKFLOW_EXECUTION_PLUGIN_NOT_FOUND)

        verify(exactly = 0) { repository.insertInstance(any(), any()) }
    }

    @Test
    fun `start proceeds when the execution plugin extension declares the id`() {
        every { repository.listDefinitions(scope) } returns listOf(definition("forge"))
        every { repository.findInstance(scope, namespace, "wf-1") } returns null
        every { pluginManager.getExtensions(FactoryWorkflowExecutionPolicy::class.java) } returns
            listOf(object : FactoryWorkflowExecutionPolicy {
                override fun getPluginId(): String = "forge"
            })
        every { submission.submit(scope, namespace, "wf-1", any(), "start", null) } returns
            SessionRunSubmissionService.SubmissionResult("sub-1", queued = true, idempotent = false, status = "pending")

        val result = start()

        assertThat(result.status).isEqualTo(201)
        verify(exactly = 1) { repository.insertInstance(scope, any()) }
        verify(exactly = 1) { submission.submit(scope, namespace, "wf-1", any(), "start", null) }
    }

    @Test
    fun `a definition without execution never consults the plugin manager`() {
        every { repository.listDefinitions(scope) } returns listOf(definition(null))
        every { repository.findInstance(scope, namespace, "wf-1") } returns null
        every { submission.submit(scope, namespace, "wf-1", any(), "start", null) } returns
            SessionRunSubmissionService.SubmissionResult("sub-2", queued = true, idempotent = false, status = "pending")

        start()

        verify(exactly = 0) { pluginManager.getExtensions(any<Class<FactoryWorkflowExecutionPolicy>>()) }
        verify(exactly = 0) { pluginManager.getPlugin(any<String>()) }
        verify(exactly = 1) { repository.insertInstance(scope, any()) }
    }
}
