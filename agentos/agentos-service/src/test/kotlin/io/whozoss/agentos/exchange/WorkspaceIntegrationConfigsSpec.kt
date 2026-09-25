package io.whozoss.agentos.exchange

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
    val ownerId = UUID.randomUUID()
    val root = ResolvedExchangeRoot(
        Path.of("/tmp/case-workspace"), ownerId,
        ExchangeWorkspace(ownerId, Path.of("/tmp/project-source")),
    )
    fun config(type: String, optOut: Boolean = false) = IntegrationConfig(
        namespaceId = ns, userId = null, name = type, integrationType = type,
        parameters = mapper.readTree("""{"workingDirectory":"/old","cwd":"/old","useCaseExchangeDirectory":${!optOut}}"""),
    )
    "tools use the provider working directory without modifying their persisted configuration" {
        listOf("BASH" to "workingDirectory", "TMUX" to "workingDirectory", "MCP_STDIO" to "cwd").forEach { (type, key) ->
            val original = config(type)
            val effective = WorkspaceIntegrationConfigs.resolve(listOf(original), root).single()
            effective.parameters!![key].asText() shouldBe "/tmp/project-source"
            original.parameters!![key].asText() shouldBe "/old"
            effective.parameters!!["workspaceId"].asText() shouldBe ownerId.toString()
        }
    }
    "TMUX defaults still enter the workspace when no parameters were saved" {
        listOf(null, mapper.nullNode()).forEach { parameters ->
            val original = config("TMUX").copy(parameters = parameters)
            val effective = WorkspaceIntegrationConfigs.resolve(listOf(original), root).single()
            effective.parameters!!["workingDirectory"].asText() shouldBe "/tmp/project-source"
            effective.parameters!!["socketName"].asText() shouldBe "agentos-${ownerId}"
            effective.parameters!!["workspaceId"].asText() shouldBe ownerId.toString()
            original.parameters shouldBe parameters
            WorkspaceIntegrationConfigs.resolve(listOf(original), root.copy(workspace = null)).single() shouldBe original
        }
    }
    "shell tools entering the workspace use the family HOME prepared by setup" {
        val home = Path.of("/tmp/workspace-support/family")
        val equipped = root.copy(workspace = ExchangeWorkspace(ownerId, Path.of("/tmp/project-source"), home))
        listOf("BASH", "TMUX").forEach { type ->
            val effective = WorkspaceIntegrationConfigs.resolve(listOf(config(type)), equipped).single()
            effective.parameters!!["workspaceHome"].asText() shouldBe home.toString()
        }
        WorkspaceIntegrationConfigs.resolve(listOf(config("BASH", optOut = true)), equipped).single()
            .parameters!!.has("workspaceHome") shouldBe false
    }
    "stdio MCP servers enter the workspace only when explicitly configured to" {
        val saved = IntegrationConfig(
            namespaceId = ns, userId = null, name = "github", integrationType = "MCP_STDIO",
            parameters = mapper.readTree("""{"command":"npx","cwd":"/opt/mcp","env":{"GITHUB_TOKEN":"secret"}}"""),
        )

        val effective = WorkspaceIntegrationConfigs.resolve(listOf(saved), root).single()

        effective shouldBe saved
        effective.parameters!!["cwd"].asText() shouldBe "/opt/mcp"
        // Not being redirected, it does not touch the workspace and stays available to readers.
        WorkspaceIntegrationConfigs.resolve(listOf(saved), root, mayWriteWorkspace = false) shouldBe listOf(saved)
    }
    "ordinary cases and integrations explicitly targeting another directory keep their configuration" {
        val configs = listOf(config("BASH"))
        WorkspaceIntegrationConfigs.resolve(configs, root.copy(workspace = null)) shouldBe configs
        val optedOut = listOf(config("BASH", true))
        WorkspaceIntegrationConfigs.resolve(optedOut, root) shouldBe optedOut
    }
    "a reader cannot receive shell or process tools targeting the shared workspace" {
        val targeting = listOf(config("BASH"), config("TMUX"), config("MCP_STDIO"))
        WorkspaceIntegrationConfigs.resolve(targeting, root, mayWriteWorkspace = false) shouldBe emptyList()
        val independent = listOf(config("BASH", true), config("HTTP"))
        WorkspaceIntegrationConfigs.resolve(independent, root, mayWriteWorkspace = false) shouldBe independent
        // An inaccessible workspace is never materialized just to filter its tools.
        WorkspaceIntegrationConfigs.resolve(targeting, root.copy(unavailableReason = "Preparing"), false) shouldBe emptyList()
    }
    val gitParameters = mapOf(
        "gitDir" to "/data/ns/repository.git/worktrees/$ownerId",
        "commonGitDir" to "/data/ns/repository.git",
        "repositoryUrl" to "https://forge.example/org/project.git",
        "mainBranch" to "main",
    )
    val gitRoot = root.copy(workspace = ExchangeWorkspace(ownerId, Path.of("/tmp/project-source"), toolParameters = gitParameters))
    fun gitConfig(parameters: String = "{}") = IntegrationConfig(
        namespaceId = ns, userId = null, name = "git", integrationType = "GIT", authSettingName = "github",
        parameters = mapper.readTree(parameters),
    )
    "Git tools receive the family worktree and the Git context the workspace provider trusts" {
        val saved = gitConfig(
            """{"workingDirectory":"/elsewhere","gitDir":"/elsewhere/.git","repositoryUrl":"https://other.example/x.git","useCaseExchangeDirectory":false}""",
        )

        val effective = WorkspaceIntegrationConfigs.resolve(listOf(saved), gitRoot).single()

        effective.parameters!!["workingDirectory"].asText() shouldBe "/tmp/project-source"
        effective.parameters!!["workspaceId"].asText() shouldBe ownerId.toString()
        gitParameters.forEach { (key, value) -> effective.parameters!![key].asText() shouldBe value }
        effective.authSettingName shouldBe "github"
        saved.parameters!!["gitDir"].asText() shouldBe "/elsewhere/.git"
    }
    "Git tools exist only inside a Git workspace the user may write" {
        val git = listOf(gitConfig())
        WorkspaceIntegrationConfigs.resolve(git, null) shouldBe emptyList()
        WorkspaceIntegrationConfigs.resolve(git, root.copy(workspace = null)) shouldBe emptyList()
        WorkspaceIntegrationConfigs.resolve(git, root) shouldBe emptyList()
        WorkspaceIntegrationConfigs.resolve(git, gitRoot, mayWriteWorkspace = false) shouldBe emptyList()
    }
    "failed and deleting workspaces do not fall back to the configured server directory" {
        shouldThrow<ExchangeUnavailableException> { WorkspaceIntegrationConfigs.resolve(listOf(config("BASH")), root.copy(unavailableReason = "Preparation failed")) }
        shouldThrow<ExchangeUnavailableException> { WorkspaceIntegrationConfigs.resolve(listOf(config("BASH")), root.copy(unavailableReason = "Removed")) }
    }
})
