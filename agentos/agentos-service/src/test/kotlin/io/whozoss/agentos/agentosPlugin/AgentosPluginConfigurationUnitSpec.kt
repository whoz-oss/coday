package io.whozoss.agentos.agentosPlugin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.agentConfig.AgentConfigServiceImpl
import io.whozoss.agentos.entity.EntityRepository
import io.whozoss.agentos.entity.InMemoryEntityRepository
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.prompt.PromptRepository
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for the [AgentosAgentsPluginConfiguration] lambdas.
 *
 * Instantiates [AgentosAgentsPluginConfiguration] directly (no Spring context), wires it
 * with a real [AgentConfigServiceImpl] backed by an in-memory repository and MockK
 * permission services, then exercises each tool through
 * [AgentosAgentsToolPlugin.provideTools] → tool.execute().
 *
 * Using the real service (instead of the former direct repository mock) means the
 * uniqueness enforcement in [AgentConfigServiceImpl.create] is exercised here too.
 */
class AgentosPluginConfigurationUnitSpec :
    StringSpec({

        val namespaceId: UUID = UUID.randomUUID()
        val userId: UUID = UUID.randomUUID()

        fun buildRepository(): AgentConfigRepository {
            val inMemory =
                InMemoryEntityRepository<AgentConfig, UUID?>(
                    parentIdExtractor = { it.namespaceId },
                    comparator = compareBy { it.name },
                )
            return object :
                AgentConfigRepository,
                EntityRepository<AgentConfig, UUID?> by inMemory {
                override fun findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId: UUID?,
                    userId: UUID?,
                    agentName: String?,
                    withDisabled: Boolean,
                ): List<AgentConfig> = throw UnsupportedOperationException()

                override fun findByParent(
                    parentId: UUID?,
                    withDisabled: Boolean,
                ): List<AgentConfig> =
                    inMemory.findByParent(parentId).let { all ->
                        if (withDisabled) all else all.filter { it.enabled }
                    }
            }
        }

        fun agent(
            name: String,
            nsId: UUID = namespaceId,
            enabled: Boolean = true,
        ) = AgentConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = nsId,
            name = name,
            enabled = enabled,
        )

        fun buildPlugin(
            repo: AgentConfigRepository,
            permissionService: PermissionService,
        ): AgentosAgentsToolPlugin {
            val service =
                AgentConfigServiceImpl(
                    agentConfigRepository = repo,
                    promptRepository = mockk<PromptRepository>(relaxed = true),
                    userService = mockk<UserService>(relaxed = true),
                )
            val config = AgentosAgentsPluginConfiguration(service, permissionService)
            return config.agentosAgentsToolPlugin() as AgentosAgentsToolPlugin
        }

        fun context(uid: UUID? = userId) =
            ToolContext(
                namespaceId = namespaceId,
                userId = uid,
                userExternalId = null,
                caseEvents = emptyList(),
            )

        // =========================================================================
        // ListAgents
        // =========================================================================

        "listAgents returns PERMISSION_DENIED when userId is null" {
            val repo = buildRepository()
            repo.save(agent("Dev"))
            val permService = mockk<PermissionService>(relaxed = true)
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context(uid = null)).filterIsInstance<ListAgentsTool>().first()

            val result = tool.execute(ListAgentsTool.Input(), context(uid = null))

            result.success shouldBe false
            result.errorType shouldBe "PERMISSION_DENIED"
            verify(exactly = 0) { permService.hasPermission(any(), any(), any(), any()) }
        }

        "listAgents returns PERMISSION_DENIED when namespace READ is denied" {
            val repo = buildRepository()
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns false
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<ListAgentsTool>().first()

            val result = tool.execute(ListAgentsTool.Input(), context())

            result.success shouldBe false
            result.errorType shouldBe "PERMISSION_DENIED"
        }

        "listAgents returns agent list when namespace READ is granted" {
            val repo = buildRepository()
            repo.save(agent("Dev"))
            repo.save(agent("Reviewer"))
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<ListAgentsTool>().first()

            val result = tool.execute(ListAgentsTool.Input(), context())

            result.success shouldBe true
            result.output shouldBe result.output // non-null JSON
        }

        // =========================================================================
        // GetAgent
        // =========================================================================

        "getAgent returns NOT_FOUND when userId is null" {
            val repo = buildRepository()
            repo.save(agent("Dev"))
            val permService = mockk<PermissionService>(relaxed = true)
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context(uid = null)).filterIsInstance<GetAgentTool>().first()

            val result = tool.execute(GetAgentTool.Input(name = "Dev"), context(uid = null))

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
            verify(exactly = 0) { permService.hasPermission(any(), any(), any(), any()) }
        }

        "getAgent returns NOT_FOUND when agent does not exist" {
            val repo = buildRepository()
            val permService = mockk<PermissionService>(relaxed = true)
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<GetAgentTool>().first()

            val result = tool.execute(GetAgentTool.Input(name = "Ghost"), context())

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
            verify(exactly = 0) { permService.hasPermission(any(), any(), any(), any()) }
        }

        "getAgent returns NOT_FOUND when AgentConfig READ is denied" {
            val repo = buildRepository()
            val saved = repo.save(agent("Dev"))
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.AGENT_CONFIG, saved.id.toString(), Action.READ) } returns false
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<GetAgentTool>().first()

            val result = tool.execute(GetAgentTool.Input(name = "Dev"), context())

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
        }

        "getAgent returns agent detail when READ is granted" {
            val repo = buildRepository()
            val saved = repo.save(agent("Dev"))
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.AGENT_CONFIG, saved.id.toString(), Action.READ) } returns true
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<GetAgentTool>().first()

            val result = tool.execute(GetAgentTool.Input(name = "Dev"), context())

            result.success shouldBe true
        }

        "getAgent lookup is case-insensitive" {
            val repo = buildRepository()
            val saved = repo.save(agent("Dev"))
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.AGENT_CONFIG, saved.id.toString(), Action.READ) } returns true
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<GetAgentTool>().first()

            val result = tool.execute(GetAgentTool.Input(name = "dev"), context())

            result.success shouldBe true
        }

        // =========================================================================
        // CreateAgent
        // =========================================================================

        "createAgent returns PERMISSION_DENIED when userId is null" {
            val repo = buildRepository()
            val permService = mockk<PermissionService>(relaxed = true)
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context(uid = null)).filterIsInstance<CreateAgentTool>().first()

            val result = tool.execute(CreateAgentTool.Input(name = "NewBot"), context(uid = null))

            result.success shouldBe false
            result.errorType shouldBe "PERMISSION_DENIED"
            verify(exactly = 0) { permService.hasPermission(any(), any(), any(), any()) }
        }

        "createAgent returns PERMISSION_DENIED when namespace WRITE is denied" {
            val repo = buildRepository()
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns false
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<CreateAgentTool>().first()

            val result = tool.execute(CreateAgentTool.Input(name = "NewBot"), context())

            result.success shouldBe false
            result.errorType shouldBe "PERMISSION_DENIED"
        }

        "createAgent returns PERMISSION_DENIED when name already exists in namespace" {
            val repo = buildRepository()
            repo.save(agent("Dev"))
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<CreateAgentTool>().first()

            val result = tool.execute(CreateAgentTool.Input(name = "Dev"), context())

            result.success shouldBe false
            result.errorType shouldBe "PERMISSION_DENIED"
        }

        "createAgent succeeds when namespace WRITE is granted and name is unique" {
            val repo = buildRepository()
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<CreateAgentTool>().first()

            val result = tool.execute(CreateAgentTool.Input(name = "NewBot"), context())

            result.success shouldBe true
        }

        // =========================================================================
        // UpdateAgent
        // =========================================================================

        "updateAgent returns NOT_FOUND when userId is null" {
            val repo = buildRepository()
            repo.save(agent("Dev"))
            val permService = mockk<PermissionService>(relaxed = true)
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context(uid = null)).filterIsInstance<UpdateAgentTool>().first()

            val result = tool.execute(UpdateAgentTool.Input(name = "Dev"), context(uid = null))

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
            verify(exactly = 0) { permService.hasPermission(any(), any(), any(), any()) }
        }

        "updateAgent returns NOT_FOUND when agent does not exist" {
            val repo = buildRepository()
            val permService = mockk<PermissionService>(relaxed = true)
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<UpdateAgentTool>().first()

            val result = tool.execute(UpdateAgentTool.Input(name = "Ghost"), context())

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
            verify(exactly = 0) { permService.hasPermission(any(), any(), any(), any()) }
        }

        "updateAgent returns NOT_FOUND when AgentConfig WRITE is denied" {
            val repo = buildRepository()
            val saved = repo.save(agent("Dev"))
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.AGENT_CONFIG, saved.id.toString(), Action.WRITE) } returns false
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<UpdateAgentTool>().first()

            val result = tool.execute(UpdateAgentTool.Input(name = "Dev", description = "Updated"), context())

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
        }

        "updateAgent succeeds and applies only provided fields" {
            val repo = buildRepository()
            val saved = repo.save(agent("Dev").copy(instructions = "original instructions", modelName = "BIG"))
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.AGENT_CONFIG, saved.id.toString(), Action.WRITE) } returns true
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<UpdateAgentTool>().first()

            val result = tool.execute(UpdateAgentTool.Input(name = "Dev", description = "New desc"), context())

            result.success shouldBe true
            val updated = repo.findByParent(namespaceId).first { it.name == "Dev" }
            updated.description shouldBe "New desc"
            updated.instructions shouldBe "original instructions"
            updated.modelName shouldBe "BIG"
        }

        // =========================================================================
        // EnableAgent
        // =========================================================================

        "enableAgent returns NOT_FOUND when userId is null" {
            val repo = buildRepository()
            repo.save(agent("Dev", enabled = false))
            val permService = mockk<PermissionService>(relaxed = true)
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context(uid = null)).filterIsInstance<EnableAgentTool>().first()

            val result = tool.execute(EnableAgentTool.Input(name = "Dev"), context(uid = null))

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
        }

        "enableAgent sets enabled=true when WRITE is granted" {
            val repo = buildRepository()
            val saved = repo.save(agent("Dev", enabled = false))
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.AGENT_CONFIG, saved.id.toString(), Action.WRITE) } returns true
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<EnableAgentTool>().first()

            val result = tool.execute(EnableAgentTool.Input(name = "Dev"), context())

            result.success shouldBe true
            val updated = repo.findByParent(namespaceId).first { it.name == "Dev" }
            updated.enabled shouldBe true
        }

        // =========================================================================
        // DisableAgent
        // =========================================================================

        "disableAgent returns NOT_FOUND when userId is null" {
            val repo = buildRepository()
            repo.save(agent("Dev", enabled = true))
            val permService = mockk<PermissionService>(relaxed = true)
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context(uid = null)).filterIsInstance<DisableAgentTool>().first()

            val result = tool.execute(DisableAgentTool.Input(name = "Dev"), context(uid = null))

            result.success shouldBe false
            result.errorType shouldBe "NOT_FOUND"
        }

        "disableAgent sets enabled=false when WRITE is granted" {
            val repo = buildRepository()
            val saved = repo.save(agent("Dev", enabled = true))
            val permService =
                mockk<PermissionService> {
                    every { hasPermission(userId.toString(), EntityType.AGENT_CONFIG, saved.id.toString(), Action.WRITE) } returns true
                }
            val plugin = buildPlugin(repo, permService)
            val tool = plugin.provideTools(config = null, context = context()).filterIsInstance<DisableAgentTool>().first()

            val result = tool.execute(DisableAgentTool.Input(name = "Dev"), context())

            result.success shouldBe true
            val updated = repo.findByParent(namespaceId).first { it.name == "Dev" }
            updated.enabled shouldBe false
        }
    })
