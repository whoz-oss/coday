package io.whozoss.agentos.workflow

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
import io.whozoss.agentos.caseFlow.SessionContextKeys
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.context.UserSessionContextResolver
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextProvider
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextResult
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

/**
 * Unit tests for [LoopWorkflowRunnerService]: guards, SEARCH phase error handling and ACT phase
 * per-entity outcomes. Case creation is observed through a recording [CaseLauncherService].
 *
 * Convention used in search tool JSON:
 * - `entityId` (root) = business entity id, e.g. `"task-alice"` — injected into the prompt.
 * - `targets[0].entityId` = AgentOS user external id, e.g. `"alice"` — used for user resolution.
 */
class LoopWorkflowRunnerUnitSpec :
    StringSpec({

        val objectMapper = ObjectMapper().registerKotlinModule()
        val namespaceId: UUID = UUID.randomUUID()
        val caseId: UUID = UUID.randomUUID()

        val triggerUser =
            User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = "ext-trigger", email = "t@example.com")

        val payload =
            objectMapper.readValue(
                """
                {
                    "search": {
                        "tool": "SearchTalents",
                        "params": {"talentId": ["6ac76692a909eaaae0b078d6"], "resolveTargets": ["MANAGER"], "next": null}
                    },
                    "act": {"agentName": "ProfileCaretaker", "promptTemplate": "This talent has not completed their profile. As their manager, I need you to help me review and improve it."}
                }
                """.trimIndent(),
                AgentLoopPayload::class.java,
            )

        data class Launch(
            val namespaceId: UUID,
            val agentName: String,
            val task: String,
            val userId: UUID,
            val sessionContext: Map<String, Any?>? = null,
        )

        // -------------------------------------------------------------------------
        // Fixture — fresh mocks per test
        // -------------------------------------------------------------------------

        class Fixture(
            objectMapper: ObjectMapper,
            maxItems: Int = 50,
            userSessionContextResolver: UserSessionContextResolver = UserSessionContextResolver(),
        ) {
            val userService = mockk<UserService>()
            val agentConfigService = mockk<AgentConfigService>()
            val launches = mutableListOf<Launch>()
            val launchedIds = mutableListOf<UUID>()
            var failLaunchForUser: UUID? = null
            private val launcher =
                CaseLauncherService { namespaceId, agentName, task, userId, sessionContext ->
                    if (failLaunchForUser != null && userId == failLaunchForUser) error("boom")
                    launches += Launch(namespaceId, agentName, task, userId, sessionContext)
                    UUID.randomUUID().also { launchedIds += it }
                }
            val runner =
                LoopWorkflowRunnerService(
                    objectMapper = objectMapper,
                    userService = userService,
                    agentConfigService = agentConfigService,
                    limitsConfig = LimitsConfigProperties(agentLoopMaxItems = maxItems),
                    userSessionContextResolver = userSessionContextResolver,
                    caseLauncherService = launcher,
                )

            fun knownUser(externalId: String): User =
                User(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = externalId,
                    email = "$externalId@example.com",
                ).also { every { userService.findByExternalId(externalId) } returns it }

            fun grantAgent(
                user: User,
                vararg agentNames: String,
            ) {
                val configs = agentNames.map { n -> mockk<AgentConfig>().also { every { it.name } returns n } }
                every {
                    agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                        any(),
                        user.metadata.id,
                        any(),
                    )
                } returns configs
            }
        }

        /**
         * Builds a mock search tool whose structured output contains items with:
         * - `entityId` = `"task-$userExternalId"` (business entity id)
         * - `targets[0].entityType` = `"User"`, `targets[0].entityId` = `userExternalId` (AgentOS user external id)
         *
         * This validates that the runner correctly reads the user id from the target with entityType='User'.
         */
        fun searchTool(
            userExternalIds: List<String>,
            next: String? = null,
            success: Boolean = true,
            structured: Boolean = true,
        ): StandardTool<*> {
            val data =
                userExternalIds.joinToString(",") { userExtId ->
                    """
                    {
                        "entityType": "TALENT",
                        "entityId": "task-$userExtId",
                        "targets": [{"entityType": "User", "entityId": "$userExtId"}]
                    }
                    """.trimIndent()
                }
            val node =
                objectMapper.readTree(
                    """{"data": [$data], "metadata": {"totalCount": ${userExternalIds.size}, "next": ${
                        next?.let {
                            "\"$it\""
                        } ?: "null"
                    }}}""",
                )
            return mockk<StandardTool<*>>().also { tool ->
                every { tool.name } returns "SearchTalents"
                coEvery { tool.executeWithJson(any(), any()) } returns
                    ToolExecutionResult(
                        output = "search output",
                        success = success,
                        structuredOutput = node.takeIf { structured },
                    )
            }
        }

        fun context(
            fixture: Fixture,
            tools: Collection<StandardTool<*>>,
            user: User? = triggerUser,
        ) = LoopRunContext(
            namespaceId = namespaceId,
            caseId = caseId,
            agentName = "loop-agent",
            triggerUser = user,
            tools = tools,
            caseEvents = emptyList(),
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

            f.runner
                .run(payload, context(f, listOf(searchTool(listOf("a"))), user = null)) { true }
                .reason() shouldContain "identified user"
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
            Fixture(objectMapper)
                .run(listOf(searchTool(listOf("a"), success = false)))
                .reason() shouldContain "returned a failure"
        }

        "aborts when the search tool returns no structured output" {
            Fixture(objectMapper)
                .run(listOf(searchTool(listOf("a"), structured = false)))
                .reason() shouldContain "no structured output"
        }

        "aborts when the structured output does not match SearchResult" {
            val tool = mockk<StandardTool<*>>()
            every { tool.name } returns "SearchTalents"
            coEvery { tool.executeWithJson(any(), any()) } returns
                ToolExecutionResult(
                    output = "x",
                    success = true,
                    structuredOutput = objectMapper.readTree("""{"items": 3}"""),
                )

            Fixture(objectMapper).run(listOf(tool)).reason() shouldContain "expected format"
        }

        "search tool receives the searchInput and the triggering user identity" {
            val f = Fixture(objectMapper)
            val tool = searchTool(emptyList())
            val json = slot<String>()
            val toolContext = slot<ToolContext>()

            f.run(listOf(tool))

            coVerify { tool.executeWithJson(capture(json), capture(toolContext)) }
            objectMapper.readTree(json.captured) shouldBe payload.search.params
            toolContext.captured.userId shouldBe triggerUser.metadata.id
            toolContext.captured.userExternalId shouldBe "ext-trigger"
            toolContext.captured.namespaceId shouldBe namespaceId
        }

        // -------------------------------------------------------------------------
        // ACT phase
        // -------------------------------------------------------------------------

        "launches one case per entity on behalf of the resolved end user and reports its id" {
            val f = Fixture(objectMapper)
            val alice = f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome = f.run(listOf(searchTool(listOf("alice")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launchedCaseIds shouldBe f.launchedIds
            outcome.summary() shouldContain f.launchedIds.single().toString()
            // task is the static promptTemplate — entityId is injected via activeContext in sessionContext
            f.launches.single().also { launch ->
                launch.namespaceId shouldBe namespaceId
                launch.agentName shouldBe "ProfileCaretaker"
                launch.task shouldBe
                    "This talent has not completed their profile. As their manager, I need you to help me review and improve it."
                launch.userId shouldBe alice.metadata.id
                @Suppress("UNCHECKED_CAST")
                val activeContext = launch.sessionContext!!["activeContext"] as List<Map<String, Any?>>
                activeContext.single()["type"] shouldBe "TALENT"
                @Suppress("UNCHECKED_CAST")
                (activeContext.single()["data"] as Map<String, Any?>)["id"] shouldBe "task-alice"
            }
        }

        "rows without entityType and with unknown fields are still processed" {
            val f = Fixture(objectMapper)
            val alice = f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            // The spec's plain ObjectMapper fails on unknown properties by default: this also checks
            // that SearchResult types ignore them explicitly, independently of the mapper configuration.
            val node =
                objectMapper.readTree(
                    """
                    {"data": [{"entityId": "task-alice", "name": "Task A", "targets": [{"entityType": "User", "entityId": "alice", "role": "OWNER"}]}],
                     "metadata": {"totalCount": 1, "next": null, "page": 1}, "extra": true}
                    """.trimIndent(),
                )
            val tool =
                mockk<StandardTool<*>>().also {
                    every { it.name } returns "SearchTalents"
                    coEvery { it.executeWithJson(any(), any()) } returns
                        ToolExecutionResult(output = "ok", success = true, structuredOutput = node)
                }

            val outcome = f.run(listOf(tool)).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 1
            f.launches.single().userId shouldBe alice.metadata.id
            f.launches.single().task shouldBe
                "This talent has not completed their profile. As their manager, I need you to help me review and improve it."
        }

        "skips entities whose targets list is null" {
            val f = Fixture(objectMapper)
            // Build a tool returning an item with no targets field
            val node =
                objectMapper.readTree(
                    """{"data": [{"entityType": "TALENT", "entityId": "task-ghost"}], "metadata": {"totalCount": 1, "next": null}}""",
                )
            val tool =
                mockk<StandardTool<*>>().also {
                    every { it.name } returns "SearchTalents"
                    coEvery { it.executeWithJson(any(), any()) } returns
                        ToolExecutionResult(output = "ok", success = true, structuredOutput = node)
                }

            val outcome = f.run(listOf(tool)).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.unknownUser shouldBe 1
            outcome.launched shouldBe 0
            f.launches shouldBe emptyList()
        }

        "skips entities whose targets list is empty" {
            val f = Fixture(objectMapper)
            val node =
                objectMapper.readTree(
                    """{"data": [{"entityType": "TALENT", "entityId": "task-ghost", "targets": []}], "metadata": {"totalCount": 1, "next": null}}""",
                )
            val tool =
                mockk<StandardTool<*>>().also {
                    every { it.name } returns "SearchTalents"
                    coEvery { it.executeWithJson(any(), any()) } returns
                        ToolExecutionResult(output = "ok", success = true, structuredOutput = node)
                }

            val outcome = f.run(listOf(tool)).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.unknownUser shouldBe 1
            outcome.launched shouldBe 0
            f.launches shouldBe emptyList()
        }

        "skips entities without a matching AgentOS user (target present but unknown)" {
            val f = Fixture(objectMapper)
            // targets[0].entityId = "ghost" but no user registered with that external id
            every { f.userService.findByExternalId("ghost") } returns null

            val outcome = f.run(listOf(searchTool(listOf("ghost")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.unknownUser shouldBe 1
            outcome.launched shouldBe 0
            f.launches shouldBe emptyList()
        }

        "refuses users who cannot access the target agent, including prefix-only matches" {
            val f = Fixture(objectMapper)
            f.knownUser("bob").also { f.grantAgent(it, "ProfileCaretaker-v2") }

            val outcome = f.run(listOf(searchTool(listOf("bob")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.noAgentAccess shouldBe 1
            f.launches shouldBe emptyList()
        }

        "a failed launch is counted and does not stop the loop" {
            val f = Fixture(objectMapper)
            val alice = f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            f.failLaunchForUser = alice.metadata.id
            f.knownUser("bob").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome =
                f.run(listOf(searchTool(listOf("alice", "bob")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.failed shouldBe 1
            outcome.launched shouldBe 1
            f.launches.map { it.task } shouldBe
                listOf("This talent has not completed their profile. As their manager, I need you to help me review and improve it.")
        }

        "processes at most agentLoopMaxItems entities" {
            val f = Fixture(objectMapper, maxItems = 2)
            listOf("a", "b", "c").forEach { f.knownUser(it).also { u -> f.grantAgent(u, "ProfileCaretaker") } }

            val outcome =
                f.run(listOf(searchTool(listOf("a", "b", "c")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 2
            outcome.overLimit shouldBe 1
            outcome.summary() shouldContain "over the limit of 2"
            verify(exactly = 0) { f.userService.findByExternalId("c") }
        }

        "reports that more pages exist" {
            val f = Fixture(objectMapper)

            val outcome =
                f.run(listOf(searchTool(emptyList(), next = "cursor-2"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.hasMorePages shouldBe true
            outcome.summary() shouldContain "only the first page"
        }

        "stops launching when interrupted" {
            val f = Fixture(objectMapper)
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome =
                f.run(listOf(searchTool(listOf("alice")))) { false }.shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.interrupted shouldBe true
            outcome.launched shouldBe 0
            f.launches shouldBe emptyList()
        }

        "uses targets entityId for user resolution, not root entityId" {
            val f = Fixture(objectMapper)
            // entityId root = "task-alice", targets[0].entityId = "alice"
            // The runner must call findByExternalId("alice"), NOT findByExternalId("task-alice")
            val alice = f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            every { f.userService.findByExternalId("task-alice") } returns null // must NOT be called

            val outcome = f.run(listOf(searchTool(listOf("alice")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 1
            f.launches.single().userId shouldBe alice.metadata.id
            verify(exactly = 0) { f.userService.findByExternalId("task-alice") }
            verify(exactly = 1) { f.userService.findByExternalId("alice") }
        }

        // -------------------------------------------------------------------------
        // sessionContext transmission
        // -------------------------------------------------------------------------

        "sessionContext from provider is forwarded to the launcher" {
            val providerContext = mapOf("talentId" to "t42", "score" to 9.5)
            val provider =
                mockk<UserContextProvider>().also {
                    every { it.provideUserContext(any(), any()) } returns UserContextResult.Success(providerContext)
                }
            val f = Fixture(objectMapper, userSessionContextResolver = UserSessionContextResolver(provider))
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome = f.run(listOf(searchTool(listOf("alice")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 1
            val ctx = f.launches.single().sessionContext!!
            ctx["talentId"] shouldBe "t42"
            ctx["score"] shouldBe 9.5
            @Suppress("UNCHECKED_CAST")
            val activeContext = ctx["activeContext"] as List<Map<String, Any?>>
            activeContext.single()["type"] shouldBe "TALENT"
        }

        "preferredLanguage from user is forwarded to the launcher in sessionContext" {
            val f = Fixture(objectMapper)
            // User with preferredLanguage set, no provider
            val alice =
                User(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    externalId = "alice",
                    email = "alice@example.com",
                    preferredLanguage = "fr",
                ).also {
                    every { f.userService.findByExternalId("alice") } returns it
                    f.grantAgent(it, "ProfileCaretaker")
                }

            val outcome = f.run(listOf(searchTool(listOf("alice")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 1
            f.launches.single().sessionContext!![SessionContextKeys.PREFERRED_LANGUAGE] shouldBe "fr"
            @Suppress("UNCHECKED_CAST")
            val activeContext = f.launches.single().sessionContext!!["activeContext"] as List<Map<String, Any?>>
            activeContext.single()["type"] shouldBe "TALENT"
        }

        "PermanentFailure from provider counts entity as failed and loop continues" {
            val provider =
                mockk<UserContextProvider>().also {
                    every { it.provideUserContext(eq("alice"), any()) } returns
                        UserContextResult.PermanentFailure("user not in external system")
                    every { it.provideUserContext(eq("bob"), any()) } returns UserContextResult.Success(null)
                }
            val f = Fixture(objectMapper, userSessionContextResolver = UserSessionContextResolver(provider))
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            f.knownUser("bob").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome =
                f.run(listOf(searchTool(listOf("alice", "bob")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.failed shouldBe 1
            outcome.launched shouldBe 1
            f.launches.single().task shouldBe
                "This talent has not completed their profile. As their manager, I need you to help me review and improve it."
        }

        "TransientFailure from provider counts entity as failed and loop continues" {
            val provider =
                mockk<UserContextProvider>().also {
                    every { it.provideUserContext(eq("alice"), any()) } returns
                        UserContextResult.TransientFailure("upstream timeout")
                    every { it.provideUserContext(eq("bob"), any()) } returns UserContextResult.Success(null)
                }
            val f = Fixture(objectMapper, userSessionContextResolver = UserSessionContextResolver(provider))
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            f.knownUser("bob").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome =
                f.run(listOf(searchTool(listOf("alice", "bob")))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.failed shouldBe 1
            outcome.launched shouldBe 1
        }
    })
