package io.whozoss.agentos.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [LoopWorkflowRunner]: guards, SEARCH phase error handling and ACT phase
 * per-entity outcomes. Case creation is observed through a recording [CaseLauncher].
 */
class LoopWorkflowRunnerUnitSpec : StringSpec({

    val objectMapper = ObjectMapper().registerKotlinModule()
    val namespaceId: UUID = UUID.randomUUID()
    val caseId: UUID = UUID.randomUUID()

    val triggerUser = User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "ext-trigger", email = "t@example.com")

    val payload =
        objectMapper.readValue(
            """
            {
                "tool": "SearchTalents",
                "searchInput": {"endDatePeriod": ["THIS_WEEK"]},
                "act": {"agentName": "talent-analyzer", "promptTemplate": "Analyse this entity: {entityId}"}
            }
            """.trimIndent(),
            AgentLoopPayload::class.java,
        )

    data class Launch(
        val namespaceId: UUID,
        val agentName: String,
        val task: String,
        val userId: UUID,
    )

    // -------------------------------------------------------------------------
    // Fixture — fresh mocks per test
    // -------------------------------------------------------------------------

    class Fixture(
        objectMapper: ObjectMapper,
        maxItems: Int = 50,
    ) {
        val userService = mockk<UserService>()
        val agentConfigService = mockk<AgentConfigService>()
        val permissionService = mockk<PermissionService>()
        val launches = mutableListOf<Launch>()
        val launchedIds = mutableListOf<UUID>()
        var failLaunchFor: String? = null
        val launcher =
            CaseLauncher { namespaceId, agentName, task, userId ->
                if (failLaunchFor != null && task.contains(failLaunchFor!!)) error("boom")
                launches += Launch(namespaceId, agentName, task, userId)
                UUID.randomUUID().also { launchedIds += it }
            }
        val runner =
            LoopWorkflowRunner(
                objectMapper = objectMapper,
                userService = userService,
                agentConfigService = agentConfigService,
                permissionService = permissionService,
                limitsConfig = LimitsConfigProperties(agentLoopMaxItems = maxItems),
            )

        init {
            every { permissionService.hasPermission(any(), EntityType.NAMESPACE, any(), Action.WRITE) } returns true
        }

        fun knownUser(externalId: String): User =
            User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = externalId, email = "$externalId@example.com")
                .also { every { userService.findByExternalId(externalId) } returns it }

        fun grantAgent(
            user: User,
            vararg agentNames: String,
        ) {
            val configs = agentNames.map { n -> mockk<AgentConfig>().also { every { it.name } returns n } }
            every { agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(any(), user.metadata.id, any()) } returns configs
        }
    }

    fun searchTool(
        entityIds: List<String>,
        next: String? = null,
        success: Boolean = true,
        structured: Boolean = true,
    ): StandardTool<*> {
        val data = entityIds.joinToString(",") { """{"entityType": "TALENT", "entityId": "$it"}""" }
        val node = objectMapper.readTree("""{"data": [$data], "metadata": {"totalCount": ${entityIds.size}, "next": ${next?.let { "\"$it\"" }}}}""")
        return mockk<StandardTool<*>>().also { tool ->
            every { tool.name } returns "SearchTalents"
            coEvery { tool.executeWithJson(any(), any()) } returns
                ToolExecutionResult(output = "search output", success = success, structuredOutput = node.takeIf { structured })
        }
    }

    fun context(
        fixture: Fixture,
        tools: Collection<StandardTool<*>>,
        user: User? = triggerUser,
        withLauncher: Boolean = true,
    ) = LoopRunContext(
        namespaceId = namespaceId,
        caseId = caseId,
        agentName = "loop-agent",
        triggerUser = user,
        tools = tools,
        caseEvents = emptyList(),
        caseLauncher = fixture.launcher.takeIf { withLauncher },
    )

    suspend fun Fixture.run(
        tools: Collection<StandardTool<*>>,
        shouldContinue: () -> Boolean = { true },
    ): LoopRunOutcome = runner.run(payload, context(this, tools), shouldContinue)

    fun LoopRunOutcome.reason(): String = shouldBeInstanceOf<LoopRunOutcome.Aborted>().reason

    // -------------------------------------------------------------------------
    // Guards
    // -------------------------------------------------------------------------

    "aborts without a triggering user" {
        val f = Fixture(objectMapper)

        f.runner.run(payload, context(f, listOf(searchTool(listOf("a"))), user = null)) { true }
            .reason() shouldContain "identified user"
    }

    "aborts without a case launcher" {
        val f = Fixture(objectMapper)

        f.runner.run(payload, context(f, listOf(searchTool(listOf("a"))), withLauncher = false)) { true }
            .reason() shouldContain "not available"
    }

    "aborts when the triggering user is not a namespace admin, without calling the search tool" {
        val f = Fixture(objectMapper)
        every { f.permissionService.hasPermission(triggerUser.metadata.id.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns false
        val tool = searchTool(listOf("a"))

        f.run(listOf(tool)).reason() shouldContain "namespace administrators"
        coVerify(exactly = 0) { tool.executeWithJson(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // SEARCH phase failures
    // -------------------------------------------------------------------------

    "aborts when the search tool is not among the agent tools" {
        Fixture(objectMapper).run(emptyList()).reason() shouldContain "Search tool 'SearchTalents' is not available"
    }

    "aborts when the search tool throws" {
        val tool = mockk<StandardTool<*>>()
        every { tool.name } returns "SearchTalents"
        coEvery { tool.executeWithJson(any(), any()) } throws IllegalStateException("backend down")

        Fixture(objectMapper).run(listOf(tool)).reason() shouldContain "backend down"
    }

    "aborts when the search tool reports a failure" {
        Fixture(objectMapper).run(listOf(searchTool(listOf("a"), success = false))).reason() shouldContain "returned a failure"
    }

    "aborts when the search tool returns no structured output" {
        Fixture(objectMapper).run(listOf(searchTool(listOf("a"), structured = false))).reason() shouldContain "no structured output"
    }

    "aborts when the structured output does not match SearchResult" {
        val tool = mockk<StandardTool<*>>()
        every { tool.name } returns "SearchTalents"
        coEvery { tool.executeWithJson(any(), any()) } returns
            ToolExecutionResult(output = "x", success = true, structuredOutput = objectMapper.readTree("""{"items": 3}"""))

        Fixture(objectMapper).run(listOf(tool)).reason() shouldContain "expected format"
    }

    "search tool receives the searchInput and the triggering user identity" {
        val f = Fixture(objectMapper)
        val tool = searchTool(emptyList())
        val json = slot<String>()
        val toolContext = slot<ToolContext>()

        f.run(listOf(tool))

        coVerify { tool.executeWithJson(capture(json), capture(toolContext)) }
        objectMapper.readTree(json.captured) shouldBe payload.searchInput
        toolContext.captured.userId shouldBe triggerUser.metadata.id
        toolContext.captured.userExternalId shouldBe "ext-trigger"
        toolContext.captured.namespaceId shouldBe namespaceId
    }

    // -------------------------------------------------------------------------
    // ACT phase
    // -------------------------------------------------------------------------

    "launches one case per entity on behalf of the resolved end user and reports its id" {
        val f = Fixture(objectMapper)
        val alice = f.knownUser("alice").also { f.grantAgent(it, "talent-analyzer") }

        val outcome = f.run(listOf(searchTool(listOf("alice")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

        outcome.launchedCaseIds shouldBe f.launchedIds
        outcome.summary() shouldContain f.launchedIds.single().toString()
        f.launches shouldBe
            listOf(Launch(namespaceId, "talent-analyzer", "Analyse this entity: alice", alice.metadata.id))
    }

    "skips entities without a matching user" {
        val f = Fixture(objectMapper)
        every { f.userService.findByExternalId("ghost") } returns null

        val outcome = f.run(listOf(searchTool(listOf("ghost")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

        outcome.unknownUser shouldBe 1
        outcome.launched shouldBe 0
        f.launches shouldBe emptyList()
    }

    "refuses users who cannot access the target agent, including prefix-only matches" {
        val f = Fixture(objectMapper)
        f.knownUser("bob").also { f.grantAgent(it, "talent-analyzer-v2") }

        val outcome = f.run(listOf(searchTool(listOf("bob")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

        outcome.noAgentAccess shouldBe 1
        f.launches shouldBe emptyList()
    }

    "a failed launch is counted and does not stop the loop" {
        val f = Fixture(objectMapper)
        f.failLaunchFor = "alice"
        f.knownUser("alice").also { f.grantAgent(it, "talent-analyzer") }
        f.knownUser("bob").also { f.grantAgent(it, "talent-analyzer") }

        val outcome = f.run(listOf(searchTool(listOf("alice", "bob")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

        outcome.failed shouldBe 1
        outcome.launched shouldBe 1
        f.launches.map { it.task } shouldBe listOf("Analyse this entity: bob")
    }

    "processes at most agentLoopMaxItems entities" {
        val f = Fixture(objectMapper, maxItems = 2)
        listOf("a", "b", "c").forEach { f.knownUser(it).also { u -> f.grantAgent(u, "talent-analyzer") } }

        val outcome = f.run(listOf(searchTool(listOf("a", "b", "c")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

        outcome.launched shouldBe 2
        outcome.overLimit shouldBe 1
        outcome.summary() shouldContain "over the limit of 2"
        verify(exactly = 0) { f.userService.findByExternalId("c") }
    }

    "reports that more pages exist" {
        val f = Fixture(objectMapper)

        val outcome = f.run(listOf(searchTool(emptyList(), next = "cursor-2"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

        outcome.hasMorePages shouldBe true
        outcome.summary() shouldContain "only the first page"
    }

    "stops launching when interrupted" {
        val f = Fixture(objectMapper)
        f.knownUser("alice").also { f.grantAgent(it, "talent-analyzer") }

        val outcome = f.run(listOf(searchTool(listOf("alice")))) { false }.shouldBeInstanceOf<LoopRunOutcome.Completed>()

        outcome.interrupted shouldBe true
        outcome.launched shouldBe 0
        f.launches shouldBe emptyList()
    }
})
