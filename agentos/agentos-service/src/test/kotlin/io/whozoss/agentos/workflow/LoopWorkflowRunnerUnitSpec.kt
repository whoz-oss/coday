package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.JsonNode
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
import io.whozoss.agentos.sdk.caseEvent.CaseEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
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
 * per-entity outcomes. Cases are started through the `CreateCase` tool, observed here through a recording
 * stand-in that answers like the real one.
 *
 * Convention used in search tool JSON:
 * - `entityId` (root) = business entity id, e.g. `"task-alice"` — injected into the context.
 * - `targets[0].entityId` = AgentOS user external id, e.g. `"alice"` — used for user resolution.
 */
class LoopWorkflowRunnerUnitSpec :
    StringSpec({

        val objectMapper = ObjectMapper().registerKotlinModule()
        val namespaceId: UUID = UUID.randomUUID()
        val caseId: UUID = UUID.randomUUID()
        val prompt = "This talent has not completed their profile. As their manager, I need you to help me review and improve it."

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
                    "act": {"agentName": "ProfileCaretaker", "promptTemplate": "$prompt"}
                }
                """.trimIndent(),
                AgentLoopPayload::class.java,
            )

        /** One call received by the stand-in `CreateCase` tool. */
        data class Call(
            val input: JsonNode,
            val toolContext: ToolContext,
        ) {
            val agentName: String get() = input["agentName"].asText()
            val prompt: String get() = input["prompt"].asText()
            val userId: UUID? get() = input["userId"]?.asText()?.let(UUID::fromString)
            val context: JsonNode get() = input["context"]
        }

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
            val calls = mutableListOf<Call>()
            val launchedIds = mutableListOf<UUID>()

            /** What the stand-in answers; set a refusal or a throw to test the loop's reading of it. */
            var answer: (Call) -> ToolExecutionResult = { success(UUID.randomUUID().also { launchedIds += it }) }

            /** Stands in for the `CreateCase` tool: records the call, answers like the real one. */
            val createCaseTool: StandardTool<*> =
                mockk<StandardTool<*>>().also { tool ->
                    every { tool.name } returns "CreateCase"
                    coEvery { tool.executeWithJson(any(), any()) } answers {
                        val call = Call(objectMapper.readTree(firstArg<String>()), secondArg())
                        calls += call
                        answer(call)
                    }
                }

            val runner =
                LoopWorkflowRunnerService(
                    objectMapper = objectMapper,
                    userService = userService,
                    agentConfigService = agentConfigService,
                    limitsConfig = LimitsConfigProperties(agentLoopMaxItems = maxItems),
                    userSessionContextResolver = userSessionContextResolver,
                )

            fun success(id: UUID): ToolExecutionResult =
                ToolExecutionResult.success(
                    "Case $id started.",
                    metadata = mapOf("caseId" to id.toString(), "agentName" to "ProfileCaretaker"),
                    structuredOutput = objectMapper.createObjectNode().put("caseId", id.toString()),
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
            tools: Collection<StandardTool<*>>,
            user: User? = triggerUser,
            caseEvents: List<CaseEvent> = emptyList(),
        ) = LoopRunContext(
            namespaceId = namespaceId,
            caseId = caseId,
            agentName = "loop-agent",
            triggerUser = user,
            tools = tools,
            caseEvents = caseEvents,
        )

        /** Runs with the search tool and the `CreateCase` stand-in both available, as a configured loop is. */
        suspend fun Fixture.run(
            search: StandardTool<*>,
            shouldContinue: () -> Boolean = { true },
        ): LoopRunOutcome = runner.run(payload, context(listOf(search, createCaseTool)), shouldContinue)

        fun LoopRunOutcome.reason(): String = shouldBeInstanceOf<LoopRunOutcome.Aborted>().reason

        // -------------------------------------------------------------------------
        // Guards
        // -------------------------------------------------------------------------

        "aborts without a triggering user" {
            val f = Fixture(objectMapper)

            f.runner
                .run(payload, context(listOf(searchTool(listOf("a")), f.createCaseTool), user = null)) { true }
                .reason() shouldContain "identified user"
        }

        "aborts when CreateCase is not among the agent tools, before searching" {
            val f = Fixture(objectMapper)
            val search = searchTool(listOf("a"))

            val reason = f.runner.run(payload, context(listOf(search))) { true }.reason()

            reason shouldContain "'CreateCase' tool is not available"
            reason shouldContain "CASE_LAUNCHER"
            coVerify(exactly = 0) { search.executeWithJson(any(), any()) }
        }

        "finds CreateCase under its integration prefix" {
            val f = Fixture(objectMapper)
            val prefixed =
                mockk<StandardTool<*>>().also { tool ->
                    every { tool.name } returns "LAUNCHER__CreateCase"
                    coEvery { tool.executeWithJson(any(), any()) } returns f.success(UUID.randomUUID())
                }
            // the end user is the triggering user, so no other-users switch is involved
            every { f.userService.findByExternalId("alice") } returns triggerUser
            f.grantAgent(triggerUser, "ProfileCaretaker")

            val outcome =
                f.runner
                    .run(payload, context(listOf(searchTool(listOf("alice")), prefixed))) { true }
                    .shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 1
        }

        // -------------------------------------------------------------------------
        // SEARCH phase failures
        // -------------------------------------------------------------------------

        "aborts when the search tool is not among the agent tools" {
            val f = Fixture(objectMapper)

            f.runner.run(payload, context(listOf(f.createCaseTool))) { true }.reason() shouldContain
                "Search tool 'SearchTalents' is not available"
        }

        "aborts when the search tool throws" {
            val tool = mockk<StandardTool<*>>()
            every { tool.name } returns "SearchTalents"
            coEvery { tool.executeWithJson(any(), any()) } throws IllegalStateException("backend down")

            Fixture(objectMapper).run(tool).reason() shouldContain "backend down"
        }

        "aborts when the search tool reports a failure" {
            Fixture(objectMapper)
                .run(searchTool(listOf("a"), success = false))
                .reason() shouldContain "returned a failure"
        }

        "aborts when the search tool returns no structured output" {
            Fixture(objectMapper)
                .run(searchTool(listOf("a"), structured = false))
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

            Fixture(objectMapper).run(tool).reason() shouldContain "expected format"
        }

        "search tool receives the searchInput and the triggering user identity" {
            val f = Fixture(objectMapper)
            val tool = searchTool(emptyList())
            val json = slot<String>()
            val toolContext = slot<ToolContext>()

            f.run(tool)

            coVerify { tool.executeWithJson(capture(json), capture(toolContext)) }
            objectMapper.readTree(json.captured) shouldBe payload.search.params
            toolContext.captured.userId shouldBe triggerUser.metadata.id
            toolContext.captured.userExternalId shouldBe "ext-trigger"
            toolContext.captured.namespaceId shouldBe namespaceId
        }

        // -------------------------------------------------------------------------
        // ACT phase
        // -------------------------------------------------------------------------

        "starts one case per entity through CreateCase, for the end user, and reports its id" {
            val f = Fixture(objectMapper)
            val alice = f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome = f.run(searchTool(listOf("alice"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launchedCaseIds shouldBe f.launchedIds
            outcome.summary() shouldContain f.launchedIds.single().toString()
            f.calls.single().also { call ->
                call.agentName shouldBe "ProfileCaretaker"
                call.prompt shouldBe prompt
                call.userId shouldBe alice.metadata.id
                // the entity travels as the context of the new case, not in the prompt
                val activeContext = call.context["activeContext"].single()
                activeContext["type"].asText() shouldBe "TALENT"
                activeContext["data"]["id"].asText() shouldBe "task-alice"
            }
        }

        "CreateCase is called as the triggering user, in the loop's namespace and with the loop's case events" {
            val f = Fixture(objectMapper)
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            val loopEvents = listOf<CaseEvent>(WarnEvent(namespaceId = namespaceId, caseId = caseId, message = "x"))

            f.runner.run(payload, context(listOf(searchTool(listOf("alice")), f.createCaseTool), caseEvents = loopEvents)) { true }

            f.calls.single().toolContext.also {
                it.userId shouldBe triggerUser.metadata.id
                it.userExternalId shouldBe "ext-trigger"
                it.namespaceId shouldBe namespaceId
                // the tool reads the loop's case from these events and records it as the origin of the new one
                it.caseEvents shouldBe loopEvents
            }
        }

        "when the end user is the triggering user, no userId is sent: that would need the other-users switch for nothing" {
            val f = Fixture(objectMapper)
            every { f.userService.findByExternalId("ext-trigger") } returns triggerUser
            f.grantAgent(triggerUser, "ProfileCaretaker")

            f.run(searchTool(listOf("ext-trigger")))

            f.calls.single().input.has("userId") shouldBe false
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

            val outcome = f.run(tool).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 1
            f.calls.single().userId shouldBe alice.metadata.id
            f.calls.single().prompt shouldBe prompt
        }

        "skips entities whose targets list is null" {
            val f = Fixture(objectMapper)
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

            val outcome = f.run(tool).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.unknownUser shouldBe 1
            outcome.launched shouldBe 0
            f.calls shouldBe emptyList()
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

            val outcome = f.run(tool).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.unknownUser shouldBe 1
            outcome.launched shouldBe 0
            f.calls shouldBe emptyList()
        }

        "skips entities without a matching AgentOS user (target present but unknown)" {
            val f = Fixture(objectMapper)
            // targets[0].entityId = "ghost" but no user registered with that external id
            every { f.userService.findByExternalId("ghost") } returns null

            val outcome = f.run(searchTool(listOf("ghost"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.unknownUser shouldBe 1
            outcome.launched shouldBe 0
            f.calls shouldBe emptyList()
        }

        "refuses users who cannot access the target agent, including prefix-only matches, without calling CreateCase" {
            val f = Fixture(objectMapper)
            f.knownUser("bob").also { f.grantAgent(it, "ProfileCaretaker-v2") }

            val outcome = f.run(searchTool(listOf("bob"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.noAgentAccess shouldBe 1
            f.calls shouldBe emptyList()
        }

        // -------------------------------------------------------------------------
        // Reading what CreateCase answers
        // -------------------------------------------------------------------------

        "a CreateCase refusal because the agent is not available counts as no agent access" {
            val f = Fixture(objectMapper)
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            f.answer = { ToolExecutionResult.error("not deployed", errorType = "AGENT_NOT_AVAILABLE") }

            val outcome = f.run(searchTool(listOf("alice"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.noAgentAccess shouldBe 1
            outcome.failed shouldBe 0
        }

        "any other CreateCase refusal counts as failed, and does not stop the loop" {
            val f = Fixture(objectMapper)
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            f.knownUser("bob").also { f.grantAgent(it, "ProfileCaretaker") }
            var first = true
            f.answer = {
                if (first) {
                    first = false
                    ToolExecutionResult.error("This tool may only start cases for the current user.", errorType = "FORBIDDEN")
                } else {
                    f.success(UUID.randomUUID().also { f.launchedIds += it })
                }
            }

            val outcome = f.run(searchTool(listOf("alice", "bob"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.failed shouldBe 1
            outcome.launched shouldBe 1
            f.calls.size shouldBe 2
        }

        "a CreateCase that throws is counted as failed and does not stop the loop" {
            val f = Fixture(objectMapper)
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            f.knownUser("bob").also { f.grantAgent(it, "ProfileCaretaker") }
            var first = true
            f.answer = {
                if (first) {
                    first = false
                    error("boom")
                } else {
                    f.success(UUID.randomUUID().also { f.launchedIds += it })
                }
            }

            val outcome = f.run(searchTool(listOf("alice", "bob"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.failed shouldBe 1
            outcome.launched shouldBe 1
        }

        "a success without a readable case id is counted as failed, not as launched" {
            val f = Fixture(objectMapper)
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            f.answer = { ToolExecutionResult.success("started, apparently") }

            val outcome = f.run(searchTool(listOf("alice"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.failed shouldBe 1
            outcome.launched shouldBe 0
        }

        "the case id is read from the metadata when there is no structured output" {
            val f = Fixture(objectMapper)
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            val id = UUID.randomUUID()
            f.answer = { ToolExecutionResult.success("ok", metadata = mapOf("caseId" to id.toString())) }

            val outcome = f.run(searchTool(listOf("alice"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launchedCaseIds shouldBe listOf(id)
        }

        // -------------------------------------------------------------------------
        // Limits
        // -------------------------------------------------------------------------

        "processes at most agentLoopMaxItems entities" {
            val f = Fixture(objectMapper, maxItems = 2)
            listOf("a", "b", "c").forEach { f.knownUser(it).also { u -> f.grantAgent(u, "ProfileCaretaker") } }

            val outcome = f.run(searchTool(listOf("a", "b", "c"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 2
            outcome.overLimit shouldBe 1
            outcome.summary() shouldContain "over the limit of 2"
            verify(exactly = 0) { f.userService.findByExternalId("c") }
        }

        "reports that more pages exist" {
            val f = Fixture(objectMapper)

            val outcome = f.run(searchTool(emptyList(), next = "cursor-2")).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.hasMorePages shouldBe true
            outcome.summary() shouldContain "only the first page"
        }

        "stops launching when interrupted" {
            val f = Fixture(objectMapper)
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome = f.run(searchTool(listOf("alice"))) { false }.shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.interrupted shouldBe true
            outcome.launched shouldBe 0
            f.calls shouldBe emptyList()
        }

        "uses targets entityId for user resolution, not root entityId" {
            val f = Fixture(objectMapper)
            // entityId root = "task-alice", targets[0].entityId = "alice"
            // The runner must call findByExternalId("alice"), NOT findByExternalId("task-alice")
            val alice = f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            every { f.userService.findByExternalId("task-alice") } returns null // must NOT be called

            val outcome = f.run(searchTool(listOf("alice"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 1
            f.calls.single().userId shouldBe alice.metadata.id
            verify(exactly = 0) { f.userService.findByExternalId("task-alice") }
            verify(exactly = 1) { f.userService.findByExternalId("alice") }
        }

        // -------------------------------------------------------------------------
        // Context handed to CreateCase
        // -------------------------------------------------------------------------

        "the provider context is handed over as the context of the new case" {
            val providerContext = mapOf("talentId" to "t42", "score" to 9.5)
            val provider =
                mockk<UserContextProvider>().also {
                    every { it.provideUserContext(any(), any()) } returns UserContextResult.Success(providerContext)
                }
            val f = Fixture(objectMapper, userSessionContextResolver = UserSessionContextResolver(provider))
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome = f.run(searchTool(listOf("alice"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 1
            val ctx = f.calls.single().context
            ctx["talentId"].asText() shouldBe "t42"
            ctx["score"].asDouble() shouldBe 9.5
            ctx["activeContext"].single()["type"].asText() shouldBe "TALENT"
        }

        "the end user's preferred language is handed over in the context" {
            val f = Fixture(objectMapper)
            // User with preferredLanguage set, no provider
            User(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                externalId = "alice",
                email = "alice@example.com",
                preferredLanguage = "fr",
            ).also {
                every { f.userService.findByExternalId("alice") } returns it
                f.grantAgent(it, "ProfileCaretaker")
            }

            val outcome = f.run(searchTool(listOf("alice"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.launched shouldBe 1
            f.calls.single().context[SessionContextKeys.PREFERRED_LANGUAGE].asText() shouldBe "fr"
            f.calls.single().context["activeContext"].single()["type"].asText() shouldBe "TALENT"
        }

        "PermanentFailure from provider counts entity as failed, without calling CreateCase for it, and the loop continues" {
            val provider =
                mockk<UserContextProvider>().also {
                    every { it.provideUserContext(eq("alice"), any()) } returns
                        UserContextResult.PermanentFailure("user not in external system")
                    every { it.provideUserContext(eq("bob"), any()) } returns UserContextResult.Success(null)
                }
            val f = Fixture(objectMapper, userSessionContextResolver = UserSessionContextResolver(provider))
            f.knownUser("alice").also { f.grantAgent(it, "ProfileCaretaker") }
            f.knownUser("bob").also { f.grantAgent(it, "ProfileCaretaker") }

            val outcome = f.run(searchTool(listOf("alice", "bob"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.failed shouldBe 1
            outcome.launched shouldBe 1
            f.calls.single().prompt shouldBe prompt
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

            val outcome = f.run(searchTool(listOf("alice", "bob"))).shouldBeInstanceOf<LoopRunOutcome.Completed>()

            outcome.failed shouldBe 1
            outcome.launched shouldBe 1
        }
    })
