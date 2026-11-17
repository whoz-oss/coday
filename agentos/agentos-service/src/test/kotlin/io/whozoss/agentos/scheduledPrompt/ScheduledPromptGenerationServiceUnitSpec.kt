package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agent.ResolvedAgentDefinition
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.aiModel.ModelResolver
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.generation.StructuredLlmClient
import io.whozoss.agentos.generation.StructuredLlmResult
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.api.agentConfig.LoopConfigGenerationResult
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedField
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedFieldSource
import io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.RecurrenceDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptGenerationRequest
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerUnit
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import io.whozoss.agentos.workflow.LoopConfigGenerationService
import jakarta.validation.Validation
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Unit tests for [ScheduledPromptGenerationService]. The LLM calls are mocked at their seams: the planning call
 * through [StructuredLlmClient], the search call through [LoopConfigGenerationService], the summary through
 * [ScheduledPromptSummaryService].
 */
class ScheduledPromptGenerationServiceUnitSpec :
    StringSpec({

        val objectMapper: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
        val validator = Validation.buildDefaultValidatorFactory().validator
        val today = LocalDate.of(2026, 3, 2)
        val clock = Clock.fixed(Instant.parse("2026-03-02T10:00:00Z"), ZoneOffset.UTC)

        val namespaceId = UUID.randomUUID()
        val userId = UUID.randomUUID()

        fun agent(
            name: String,
            mode: ExecutionMode,
            filesystem: Boolean = false,
            platform: Boolean = false,
        ) = AgentConfig(
            metadata = EntityMetadata(id = UUID.randomUUID(), version = if (filesystem) null else 0L),
            namespaceId = if (platform) null else namespaceId,
            name = name,
            description = "$name description",
            executionMode = mode,
        )

        val loopA = agent("LoopA", ExecutionMode.LOOP)
        val loopB = agent("LoopB", ExecutionMode.LOOP)
        val luigi = agent("Luigi", ExecutionMode.ADVANCED)
        val mario = agent("Mario", ExecutionMode.SIMPLE)

        val agentConfigService = mockk<AgentConfigService>()
        val agentService = mockk<AgentService>()
        val modelResolver = mockk<ModelResolver>()
        val structuredLlmClient = mockk<StructuredLlmClient>()
        val loopConfigGenerationService = mockk<LoopConfigGenerationService>()
        val scheduledPromptService = mockk<ScheduledPromptService>()
        val summaryService = mockk<ScheduledPromptSummaryService>()
        val userService = mockk<UserService>()
        val service =
            ScheduledPromptGenerationService(
                agentConfigService = agentConfigService,
                agentService = agentService,
                modelResolver = modelResolver,
                structuredLlmClient = structuredLlmClient,
                loopConfigGenerationService = loopConfigGenerationService,
                scheduledPromptService = scheduledPromptService,
                summaryService = summaryService,
                userService = userService,
                objectMapper = objectMapper,
                validator = validator,
                clock = clock,
            )

        val recurrence =
            RecurrenceDto(unit = SchedulerUnit.WEEK, days = listOf(DayOfWeek.MONDAY), timeUtc = LocalTime.of(9, 0))
        val planning = PlanningDto(startDate = today, endType = SchedulerEndType.NEVER)
        val searchBlock =
            objectMapper.readTree("""{"search": {"tool": "searchAgentUsers", "params": {"agentConfigId": "x"}}}""")

        fun request(
            loopAgentName: String? = null,
            targetAgentName: String? = null,
            prompt: String? = null,
            recurrence: RecurrenceDto? = null,
            planning: PlanningDto? = null,
            searchToolName: String? = null,
            baseFlowConfiguration: JsonNode? = null,
        ) = ScheduledPromptGenerationRequest(
            namespaceId = namespaceId,
            description = "Every Monday at 9:00, ask Luigi for a remarkable story",
            loopAgentName = loopAgentName,
            targetAgentName = targetAgentName,
            prompt = prompt,
            searchToolName = searchToolName,
            baseFlowConfiguration = baseFlowConfiguration,
            recurrence = recurrence,
            planning = planning,
        )

        /** A complete planning answer, with [overrides] replacing top-level keys (a null value removes the key). */
        fun planAnswer(overrides: Map<String, Any?> = emptyMap()): StructuredLlmResult {
            val base =
                mutableMapOf<String, Any?>(
                    "loopAgentName" to "LoopB",
                    "targetAgentName" to "Luigi",
                    "prompt" to "Tell me a remarkable story for today.",
                    "name" to "Weekly story",
                    "recurrence" to mapOf("unit" to "WEEK", "days" to listOf("MONDAY"), "timeUtc" to "09:00"),
                    "planning" to mapOf("startDate" to today.toString(), "endType" to "NEVER"),
                    "rationale" to mapOf("loopAgent" to "because", "targetAgent" to "storyteller"),
                )
            overrides.forEach { (key, value) -> if (value == null) base.remove(key) else base[key] = value }
            val text = objectMapper.writeValueAsString(base)
            return StructuredLlmResult(json = objectMapper.readTree(text), rawText = text)
        }

        fun json(text: String): JsonNode = objectMapper.readTree(text)

        val definition = mockk<ResolvedAgentDefinition>(relaxed = true)

        beforeTest {
            clearAllMocks()
            every { agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null) } returns
                    listOf(loopA, loopB, luigi, mario)
            every {
                modelResolver.resolve(
                    namespaceId,
                    userId
                )
            } returns (mockk<AiModel>(relaxed = true) to mockk<AiProvider>(relaxed = true))
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns planAnswer()
            coEvery { agentService.resolveDefinition(any(), namespaceId, userId) } returns definition
            coEvery { loopConfigGenerationService.generate(any(), any(), any(), any(), any()) } returns
                    LoopConfigGenerationResult(loopConfig = searchBlock)
            every { scheduledPromptService.isNameTaken(any(), any(), any()) } returns false
            every { userService.findById(any()) } returns null
            coEvery { summaryService.summarize(any(), any(), any(), any()) } returns
                    ScheduledPromptSummaryService.SummaryOutcome(
                        summary = "**Every Monday**, *Luigi* will: tell a story",
                        warning = null
                    )
        }

        // -------------------------------------------------------------------------
        // Happy path and assembly
        // -------------------------------------------------------------------------

        "builds a disabled, valid proposal whose flowConfiguration has the search block and act.agentName, without act.prompt" {
            val result = service.generate(namespaceId, userId, request())

            val proposal = result.scheduledPrompt.shouldNotBeNull()
            proposal.id.shouldBeNull()
            proposal.enabled shouldBe false
            validator.validate(proposal) shouldBe emptySet()
            proposal.namespaceId shouldBe namespaceId
            proposal.agentConfigId shouldBe loopB.metadata.id
            proposal.promptContent shouldBe "Tell me a remarkable story for today."
            proposal.name shouldBe "Weekly story"
            proposal.recurrence shouldBe recurrence
            proposal.planning shouldBe planning
            val flow = proposal.flowConfiguration.shouldNotBeNull()
            flow["search"] shouldBe searchBlock["search"]
            flow["act"]["agentName"].asText() shouldBe "Luigi"
            flow["act"].has("prompt") shouldBe false
            result.warnings shouldBe emptyList()
            result.rawResponse.shouldBeNull()
        }

        "rawResponse is the raw text of the step that failed, and never comes with a proposal" {
            // planning answer unusable
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns StructuredLlmResult(
                json = null,
                rawText = "plan raw"
            )
            service.generate(namespaceId, userId, request()).rawResponse shouldBe "plan raw"

            // planning fine, search answer unusable
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns planAnswer()
            coEvery { loopConfigGenerationService.generate(any(), any(), any(), any(), any()) } returns
                    LoopConfigGenerationResult(loopConfig = null, rawResponse = "search raw", warning = "unparseable")
            service.generate(namespaceId, userId, request()).rawResponse shouldBe "search raw"

            // planning fine but an agent outside the candidates: no proposal, yet the planning answer was usable
            coEvery {
                structuredLlmClient.call(
                    any(),
                    any(),
                    any(),
                    any()
                )
            } returns planAnswer(mapOf("loopAgentName" to "Ghost"))
            service.generate(namespaceId, userId, request()).rawResponse.shouldBeNull()
        }

        "the search call uses the loop agent's definition and gets the target agent's name and id as context" {
            val context = slot<String>()
            coEvery {
                loopConfigGenerationService.generate(
                    definition,
                    "searchAgentUsers",
                    any(),
                    capture(context),
                    any()
                )
            } returns
                    LoopConfigGenerationResult(loopConfig = searchBlock)

            service.generate(namespaceId, userId, request(searchToolName = "searchAgentUsers"))

            coVerify { agentService.resolveDefinition(loopB.metadata.id, namespaceId, userId) }
            context.captured shouldContain "Luigi"
            context.captured shouldContain luigi.metadata.id.toString()
        }

        "without a base flow configuration, the search call gets no base parameters" {
            service.generate(namespaceId, userId, request())

            coVerify { loopConfigGenerationService.generate(any(), null, any(), any(), isNull()) }
        }

        "decisions tell provided fields from inferred ones" {
            val result =
                service.generate(namespaceId, userId, request(targetAgentName = "luigi", recurrence = recurrence))

            result.decisions.associate { it.field to it.source } shouldBe
                    mapOf(
                        GeneratedField.LOOP_AGENT to GeneratedFieldSource.INFERRED,
                        GeneratedField.TARGET_AGENT to GeneratedFieldSource.PROVIDED,
                        GeneratedField.PROMPT to GeneratedFieldSource.INFERRED,
                        GeneratedField.NAME to GeneratedFieldSource.INFERRED,
                        GeneratedField.RECURRENCE to GeneratedFieldSource.PROVIDED,
                        GeneratedField.PLANNING to GeneratedFieldSource.INFERRED,
                        GeneratedField.SEARCH to GeneratedFieldSource.INFERRED,
                    )
            result.decisions.first { it.field == GeneratedField.TARGET_AGENT }.value shouldBe "Luigi"
            result.decisions.first { it.field == GeneratedField.LOOP_AGENT }.rationale shouldBe "because"
        }

        "provided values win over the LLM's answer" {
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns
                    planAnswer(mapOf("targetAgentName" to "Mario", "prompt" to "LLM prompt"))

            val proposal =
                service.generate(
                    namespaceId,
                    userId,
                    request(targetAgentName = "Luigi", prompt = "My prompt")
                ).scheduledPrompt.shouldNotBeNull()

            proposal.promptContent shouldBe "My prompt"
            proposal.flowConfiguration!!["act"]["agentName"].asText() shouldBe "Luigi"
        }

        "everything provided: no planning call, and the name is derived from the description" {
            val result =
                service.generate(
                    namespaceId,
                    userId,
                    request(
                        loopAgentName = "LoopA",
                        targetAgentName = "Luigi",
                        prompt = "p",
                        recurrence = recurrence,
                        planning = planning
                    ),
                )

            coVerify(exactly = 0) { structuredLlmClient.call(any(), any(), any(), any()) }
            result.scheduledPrompt.shouldNotBeNull().name shouldBe "Every Monday at 9:00, ask Luigi for a remarkable story"
        }

        "a generated name longer than the bound is cut to it" {
            coEvery {
                structuredLlmClient.call(
                    any(),
                    any(),
                    any(),
                    any()
                )
            } returns planAnswer(mapOf("name" to "x".repeat(200)))

            service.generate(namespaceId, userId, request()).scheduledPrompt.shouldNotBeNull().name.length shouldBe
                    ScheduledPromptGenerationService.MAX_GENERATED_NAME_LENGTH
        }

        "a single LOOP candidate is picked without asking the LLM for it" {
            every {
                agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                    namespaceId,
                    userId,
                    null
                )
            } returns listOf(loopA, luigi)

            val result = service.generate(namespaceId, userId, request())

            result.scheduledPrompt.shouldNotBeNull().agentConfigId shouldBe loopA.metadata.id
            result.decisions.first { it.field == GeneratedField.LOOP_AGENT }.rationale shouldBe "only LOOP agent available"
        }

        "a namespace agent shadows a platform agent of the same name, and filesystem agents are no LOOP candidates" {
            val platformLoop = agent("LoopA", ExecutionMode.LOOP, platform = true)
            val filesystemLoop = agent("FsLoop", ExecutionMode.LOOP, filesystem = true)
            every { agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null) } returns
                    listOf(platformLoop, loopA, filesystemLoop, luigi)

            service.generate(
                namespaceId,
                userId,
                request()
            ).scheduledPrompt.shouldNotBeNull().agentConfigId shouldBe loopA.metadata.id
        }

        // -------------------------------------------------------------------------
        // Base flow configuration
        // -------------------------------------------------------------------------

        val base =
            json(
                """
                {
                  "search": { "tool": "searchAgentUsers", "params": { "agentConfigId": "d715a9da" } },
                  "act": { "agentName": "Luigi", "prompt": "Quelle histoire marquante pour aujourd'hui ?" }
                }
                """.trimIndent(),
            )

        "the base search.tool is the search tool, reported as provided, and its params are the base parameters" {
            val result = service.generate(namespaceId, userId, request(baseFlowConfiguration = base))

            coVerify {
                loopConfigGenerationService.generate(
                    definition,
                    "searchAgentUsers",
                    any(),
                    any(),
                    base["search"]["params"]
                )
            }
            result.decisions.first { it.field == GeneratedField.SEARCH }.source shouldBe GeneratedFieldSource.PROVIDED
        }

        "the base act.agentName and act.prompt are the target agent and the prompt, both provided" {
            val result = service.generate(namespaceId, userId, request(baseFlowConfiguration = base))

            val proposal = result.scheduledPrompt.shouldNotBeNull()
            proposal.flowConfiguration!!["act"]["agentName"].asText() shouldBe "Luigi"
            proposal.promptContent shouldBe "Quelle histoire marquante pour aujourd'hui ?"
            result.decisions.associate { it.field to it.source }.let {
                it[GeneratedField.TARGET_AGENT] shouldBe GeneratedFieldSource.PROVIDED
                it[GeneratedField.PROMPT] shouldBe GeneratedFieldSource.PROVIDED
            }
        }

        "a base that settles everything left, with the schedule, needs no planning call" {
            service.generate(
                namespaceId,
                userId,
                request(
                    loopAgentName = "LoopA",
                    recurrence = recurrence,
                    planning = planning,
                    baseFlowConfiguration = base
                ),
            )

            coVerify(exactly = 0) { structuredLlmClient.call(any(), any(), any(), any()) }
        }

        "explicit request fields win over their counterparts in the base" {
            val result =
                service.generate(
                    namespaceId,
                    userId,
                    request(
                        targetAgentName = "Mario",
                        prompt = "My prompt",
                        searchToolName = "otherTool",
                        baseFlowConfiguration = base
                    ),
                )

            val proposal = result.scheduledPrompt.shouldNotBeNull()
            proposal.flowConfiguration!!["act"]["agentName"].asText() shouldBe "Mario"
            proposal.promptContent shouldBe "My prompt"
            coVerify {
                loopConfigGenerationService.generate(
                    any(),
                    "otherTool",
                    any(),
                    any(),
                    base["search"]["params"]
                )
            }
        }

        "a partial base only provides what it holds" {
            service.generate(
                namespaceId,
                userId,
                request(baseFlowConfiguration = json("""{"search": {"params": {"a": 1}}}"""))
            )

            // no tool in the base: the tool is still selected; the params are still the base
            coVerify { loopConfigGenerationService.generate(any(), null, any(), any(), json("""{"a": 1}""")) }
        }

        "an explicit JSON null base is no base" {
            service.generate(namespaceId, userId, request(baseFlowConfiguration = NullNode.instance))

            coVerify { loopConfigGenerationService.generate(any(), null, any(), any(), isNull()) }
        }

        "a base part of the wrong type, or an unknown base target agent, is a 400 before any LLM call" {
            forAll(
                row("""{"search": {"tool": 42}}""", "search.tool"),
                row("""{"search": {"params": [1, 2]}}""", "search.params"),
                row("""{"act": {"agentName": {"x": 1}}}""", "act.agentName"),
                row("""{"act": {"prompt": true}}""", "act.prompt"),
                row("""{"act": {"agentName": "Ghost"}}""", "Ghost"),
            ) { text, expected ->
                shouldThrow<BadRequestException> {
                    service.generate(namespaceId, userId, request(baseFlowConfiguration = json(text)))
                }.message shouldContain expected
            }
            coVerify(exactly = 0) { structuredLlmClient.call(any(), any(), any(), any()) }
        }

        "a malformed base part is a 400 even when the request field overrides it" {
            shouldThrow<BadRequestException> {
                service.generate(
                    namespaceId,
                    userId,
                    request(
                        searchToolName = "searchAgentUsers",
                        baseFlowConfiguration = json("""{"search": {"tool": 42}}""")
                    ),
                )
            }
        }

        "the request validation refuses a base that is not an object, or too large" {
            forAll(
                row<JsonNode>(json("[1, 2]")),
                row<JsonNode>(json("\"text\"")),
                row<JsonNode>(
                    json(
                        """{"search": {"params": {"x": "${"y".repeat(ScheduledPromptGenerationRequest.MAX_BASE_FLOW_CONFIGURATION_LENGTH)}"}}}""",
                    ),
                ),
            ) { node ->
                validator.validate(request(baseFlowConfiguration = node)).map { it.message }
                    .joinToString() shouldContain
                        "baseFlowConfiguration"
            }
            validator.validate(request(baseFlowConfiguration = base)) shouldBe emptySet()
            validator.validate(request(baseFlowConfiguration = NullNode.instance)) shouldBe emptySet()
        }

        // -------------------------------------------------------------------------
        // Refusals
        // -------------------------------------------------------------------------

        "an unknown provided agent is a 400 naming the available ones" {
            forAll(
                row(request(loopAgentName = "Ghost"), "LoopA"),
                row(request(targetAgentName = "Ghost"), "Luigi"),
                // a LOOP agent is not a target, nor a target a loop agent
                row(request(targetAgentName = "LoopA"), "Mario"),
                row(request(loopAgentName = "Luigi"), "LoopB"),
            ) { req, available ->
                shouldThrow<BadRequestException> {
                    service.generate(
                        namespaceId,
                        userId,
                        req
                    )
                }.message shouldContain available
            }
        }

        "no LOOP agent, or no target agent, available is a 422" {
            forAll(
                row(listOf(luigi, mario)),
                row(listOf(loopA, loopB)),
            ) { agents ->
                every {
                    agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(
                        namespaceId,
                        userId,
                        null
                    )
                } returns agents
                shouldThrow<UnprocessableEntityException> { service.generate(namespaceId, userId, request()) }
            }
        }

        // -------------------------------------------------------------------------
        // Unusable LLM answers
        // -------------------------------------------------------------------------

        "an LLM choice outside the candidates, or a missing one, yields no proposal and a warning" {
            forAll(
                row(mapOf<String, Any?>("loopAgentName" to "Ghost"), "Ghost"),
                row(mapOf<String, Any?>("targetAgentName" to "LoopA"), "LoopA"),
                row(mapOf<String, Any?>("targetAgentName" to null), "did not choose"),
                row(mapOf<String, Any?>("prompt" to null), "prompt"),
            ) { overrides, expected ->
                coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns planAnswer(overrides)

                val result = service.generate(namespaceId, userId, request())

                result.scheduledPrompt.shouldBeNull()
                result.warnings.joinToString() shouldContain expected
            }
            coVerify(exactly = 0) { loopConfigGenerationService.generate(any(), any(), any(), any(), any()) }
        }

        "an LLM answer with no JSON object yields no proposal and the raw text" {
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns StructuredLlmResult(
                json = null,
                rawText = "sorry"
            )

            val result = service.generate(namespaceId, userId, request())

            result.scheduledPrompt.shouldBeNull()
            result.rawResponse shouldBe "sorry"
        }

        "an invalid recurrence from the LLM yields no proposal" {
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns
                    planAnswer(mapOf("recurrence" to mapOf("unit" to "DAY", "timeUtc" to "09:00")))

            val result = service.generate(namespaceId, userId, request())

            result.scheduledPrompt.shouldBeNull()
            result.warnings.joinToString() shouldContain "recurrence"
        }

        "an invalid planning from the LLM falls back to from-today-never-ends, with a warning" {
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns
                    planAnswer(mapOf("planning" to mapOf("startDate" to today.toString(), "endType" to "ON_DATE")))

            val result = service.generate(namespaceId, userId, request())

            result.scheduledPrompt.shouldNotBeNull().planning shouldBe PlanningDto(
                startDate = today,
                endType = SchedulerEndType.NEVER
            )
            result.warnings.joinToString() shouldContain "planning"
        }

        "a name already taken in the namespace keeps the proposal, with a warning" {
            every { scheduledPromptService.isNameTaken(namespaceId, null, "Weekly story") } returns true

            val result = service.generate(namespaceId, userId, request())

            result.scheduledPrompt.shouldNotBeNull()
            result.warnings.joinToString() shouldContain "already exists"
        }

        // -------------------------------------------------------------------------
        // Summary
        // -------------------------------------------------------------------------

        "a proposal comes with the summary, written from the final values in the caller's preferred language" {
            val input = slot<ScheduledPromptSummaryService.SummaryInput>()
            every { userService.findById(userId) } returns
                    User(metadata = EntityMetadata(id = userId), externalId = "u", preferredLanguage = "fr")
            coEvery { summaryService.summarize(namespaceId, userId, capture(input), "fr") } returns
                    ScheduledPromptSummaryService.SummaryOutcome(summary = "résumé", warning = null)

            val result = service.generate(namespaceId, userId, request(targetAgentName = "Luigi", prompt = "My prompt"))

            result.summary shouldBe "résumé"
            input.captured.targetAgentName shouldBe "Luigi"
            input.captured.prompt shouldBe "My prompt"
            input.captured.recurrence shouldBe recurrence
            input.captured.searchTool shouldBe "searchAgentUsers"
            input.captured.searchParams shouldBe searchBlock["search"]["params"]
        }

        "no proposal means no summary call" {
            coEvery {
                structuredLlmClient.call(
                    any(),
                    any(),
                    any(),
                    any()
                )
            } returns planAnswer(mapOf("loopAgentName" to "Ghost"))

            val result = service.generate(namespaceId, userId, request())

            result.summary.shouldBeNull()
            coVerify(exactly = 0) { summaryService.summarize(any(), any(), any(), any()) }
        }

        "a failed summary keeps the proposal, with the summary's warning" {
            coEvery { summaryService.summarize(any(), any(), any(), any()) } returns
                    ScheduledPromptSummaryService.SummaryOutcome(
                        summary = null,
                        warning = "No summary could be generated."
                    )

            val result = service.generate(namespaceId, userId, request())

            result.scheduledPrompt.shouldNotBeNull()
            result.summary.shouldBeNull()
            result.warnings shouldContainExactly listOf("No summary could be generated.")
        }

        "a failed search step yields no proposal, with its warning and raw response" {
            coEvery { loopConfigGenerationService.generate(any(), any(), any(), any(), any()) } returns
                    LoopConfigGenerationResult(loopConfig = null, rawResponse = "raw", warning = "no tool")

            val result = service.generate(namespaceId, userId, request())

            result.scheduledPrompt.shouldBeNull()
            result.warnings shouldContainExactly listOf("no tool")
            result.rawResponse shouldBe "raw"
        }
    })
