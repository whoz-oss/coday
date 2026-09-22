package io.whozoss.agentos.git

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.integrationConfig.IntegrationConfig
import java.nio.file.Path
import java.util.UUID

class WorkspaceIntegrationConfigsSpec : StringSpec({
    val mapper = jacksonObjectMapper()
    val ns = UUID.randomUUID()
    val binding = CaseResourceBinding(rootCaseId = UUID.randomUUID(), namespaceId = ns, integrationConfigId = UUID.randomUUID(), status = CaseResourceStatus.READY)
    val root = ResolvedExchangeRoot(Path.of("/tmp/case-worktree"), binding)
    fun config(type: String, optOut: Boolean = false) = IntegrationConfig(
        namespaceId = ns, userId = null, name = type, integrationType = type,
        parameters = mapper.readTree("""{"workingDirectory":"/old","cwd":"/old","useCaseExchangeDirectory":${!optOut}}"""),
    )
    "tools use the shared worktree without modifying their persisted configuration" {
        listOf("BASH" to "workingDirectory", "TMUX" to "workingDirectory", "MCP_STDIO" to "cwd").forEach { (type, key) ->
            val original = config(type)
            val effective = WorkspaceIntegrationConfigs.resolve(listOf(original), root).single()
            effective.parameters!![key].asText() shouldBe "/tmp/case-worktree/repo"
            original.parameters!![key].asText() shouldBe "/old"
            effective.parameters!!["workspaceId"].asText() shouldBe binding.rootCaseId.toString()
        }
    }
    "TMUX defaults still enter the workspace when no parameters were saved" {
        listOf(null, mapper.nullNode()).forEach { parameters ->
            val original = config("TMUX").copy(parameters = parameters)
            val effective = WorkspaceIntegrationConfigs.resolve(listOf(original), root).single()
            effective.parameters!!["workingDirectory"].asText() shouldBe "/tmp/case-worktree/repo"
            effective.parameters!!["socketName"].asText() shouldBe "agentos-${binding.rootCaseId}"
            effective.parameters!!["workspaceId"].asText() shouldBe binding.rootCaseId.toString()
            original.parameters shouldBe parameters
            WorkspaceIntegrationConfigs.resolve(listOf(original), root.copy(binding = null)).single() shouldBe original
        }
    }
    "ordinary cases and integrations explicitly targeting another directory keep their configuration" {
        val configs = listOf(config("BASH"))
        WorkspaceIntegrationConfigs.resolve(configs, root.copy(binding = null)) shouldBe configs
        val optedOut = listOf(config("BASH", true))
        WorkspaceIntegrationConfigs.resolve(optedOut, root) shouldBe optedOut
    }
    "failed and deleting workspaces do not fall back to the configured server directory" {
        shouldThrow<CaseWorkspaceUnavailableException> { WorkspaceIntegrationConfigs.resolve(listOf(config("BASH")), root.copy(binding = binding.copy(status = CaseResourceStatus.FAILED))) }
        shouldThrow<CaseWorkspaceUnavailableException> { WorkspaceIntegrationConfigs.resolve(listOf(config("BASH")), root.copy(binding = binding.copy(status = CaseResourceStatus.DELETING))) }
    }
})
