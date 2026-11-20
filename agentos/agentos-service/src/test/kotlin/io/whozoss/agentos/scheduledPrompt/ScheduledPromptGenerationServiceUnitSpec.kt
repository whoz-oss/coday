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
import io.kotest.matchers.string.shouldNotContain
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
import io.whozoss.agentos.sdk.api.scheduledPrompt.FlowConfigurationPreset
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedField
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedFieldDecision
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
 * [ScheduledPromptSummaryService]. The preset choice is mocked at [FlowConfigurationPresetSelector], tested on its own.
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
        val presetSelector = mockk<FlowConfigurationPresetSelector>()
        val service =
            ScheduledPromptGenerationService(
                agentConfigService = agentConfigService,
                agentService = agentService,
                modelResolver = modelResolver,
                structuredLlmClient = structuredLlmClient,
                presetSelector = presetSelector,
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
            flowConfigurationPresets: List<FlowConfigurationPreset>? = null,
        ) = ScheduledPromptGenerationRequest(
            namespaceId = namespaceId,
            description = "Every Monday at 9:00, ask Luigi for a remarkable story",
            loopAgentName = loopAgentName,
            targetAgentName = targetAgentName,
            prompt = prompt,
            searchToolName = searchToolName,
            baseFlowConfiguration = baseFlowConfiguration,
            flowConfigurationPresets = flowConfigurationPresets,
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
            // The candidates are the agents of the namespace: findDeployedByNamespaceIdAndUserIdAndName is not stubbed,
            // so that a lookup of what is deployed to the caller fails the test.
            every { agentConfigService.findByNamespace(namespaceId, false) } returns listOf(loopB, luigi, mario)
            every { agentConfigService.findByNamespace(null, false) } returns emptyList()
            every {
                modelResolver.resolve(
                    namespaceId,
                    userId,
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
                    warning = null,
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
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns
                StructuredLlmResult(
                    json = null,
                    rawText = "plan raw",
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
                    any(),
                )
            } returns planAnswer(mapOf("targetAgentName" to "Ghost"))
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
                    any(),
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
            result.decisions.first { it.field == GeneratedField.LOOP_AGENT }.rationale shouldBe
                "only LOOP agent of the namespace"
        }

        "provided values win over the LLM's answer" {
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns
                planAnswer(mapOf("targetAgentName" to "Mario", "prompt" to "LLM prompt"))

            val proposal =
                service
                    .generate(
                        namespaceId = namespaceId,
                        userId = userId,
                        request = request(targetAgentName = "Luigi", prompt = "My prompt"),
                    ).scheduledPrompt
                    .shouldNotBeNull()

            proposal.promptContent shouldBe "My prompt"
            proposal.flowConfiguration!!["act"]["agentName"].asText() shouldBe "Luigi"
        }

        "everything provided: no planning call, and the name is derived from the description" {
            val result =
                service.generate(
                    namespaceId,
                    userId,
                    request(
                        loopAgentName = "LoopB",
                        targetAgentName = "Luigi",
                        prompt = "p",
                        recurrence = recurrence,
                        planning = planning,
                    ),
                )

            coVerify(exactly = 0) { structuredLlmClient.call(any(), any(), any(), any()) }
            result.scheduledPrompt.shouldNotBeNull().name shouldBe "Every Monday at 9:00, ask Luigi for a remarkable story"
        }

        "a generated name longer than the bound is cut to it" {
            coEvery {
                structuredLlmClient.call(
                    model = any(),
                    provider = any(),
                    systemPrompt = any(),
                    userMessage = any(),
                )
            } returns planAnswer(mapOf("name" to "x".repeat(200)))

            service
                .generate(namespaceId = namespaceId, userId = userId, request = request())
                .scheduledPrompt
                .shouldNotBeNull()
                .name.length shouldBe
                ScheduledPromptGenerationService.MAX_GENERATED_NAME_LENGTH
        }

        "the loop agent is the only LOOP agent of the namespace, picked without asking the LLM, even if the LLM names another" {
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns
                planAnswer(mapOf("loopAgentName" to "Ghost"))

            val result = service.generate(namespaceId, userId, request())

            result.scheduledPrompt.shouldNotBeNull().agentConfigId shouldBe loopB.metadata.id
            result.decisions.first { it.field == GeneratedField.LOOP_AGENT }.rationale shouldBe
                "only LOOP agent of the namespace"
        }

        "the candidates do not depend on what is deployed to the caller: a designer without the loop agent can generate" {
            val result = service.generate(namespaceId, userId, request())

            result.scheduledPrompt.shouldNotBeNull().agentConfigId shouldBe loopB.metadata.id
            coVerify(exactly = 0) { agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(any(), any(), any()) }
        }

        "filesystem and platform LOOP agents are no candidates, and a namespace target shadows a platform one" {
            val platformLoop = agent("PlatformLoop", ExecutionMode.LOOP, platform = true)
            val filesystemLoop = agent("FsLoop", ExecutionMode.LOOP, filesystem = true)
            val platformLuigi = agent("Luigi", ExecutionMode.ADVANCED, platform = true)
            val platformPeach = agent("Peach", ExecutionMode.SIMPLE, platform = true)
            every { agentConfigService.findByNamespace(namespaceId, false) } returns
                listOf(
                    loopB,
                    filesystemLoop,
                    luigi,
                )
            every { agentConfigService.findByNamespace(null, false) } returns
                listOf(
                    platformLoop,
                    platformLuigi,
                    platformPeach,
                )

            val proposal =
                service
                    .generate(
                        namespaceId = namespaceId,
                        userId = userId,
                        request = request(targetAgentName = "Luigi"),
                    ).scheduledPrompt
                    .shouldNotBeNull()
            proposal.agentConfigId shouldBe loopB.metadata.id

            // a platform agent is a target when no namespace agent shadows it
            service
                .generate(namespaceId, userId, request(targetAgentName = "Peach"))
                .scheduledPrompt
                .shouldNotBeNull()
                .flowConfiguration!!["act"]["agentName"]
                .asText() shouldBe "Peach"
            // the namespace Luigi, not the platform one, is the target the search step is told about
            coVerify {
                loopConfigGenerationService.generate(
                    any(),
                    any(),
                    any(),
                    match { it.contains(luigi.metadata.id.toString()) },
                    any(),
                )
            }
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
                    base["search"]["params"],
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
                    loopAgentName = "LoopB",
                    recurrence = recurrence,
                    planning = planning,
                    baseFlowConfiguration = base,
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
                        baseFlowConfiguration = base,
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
                    base["search"]["params"],
                )
            }
        }

        "a partial base only provides what it holds" {
            service.generate(
                namespaceId,
                userId,
                request(baseFlowConfiguration = json("""{"search": {"params": {"a": 1}}}""")),
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

        "the base's resolveTargets is given to the planner as the recipient of each case, only when usable" {
            fun plannerMessageFor(params: String?): String {
                val message = slot<String>()
                coEvery { structuredLlmClient.call(any(), any(), any(), capture(message)) } returns planAnswer()
                val baseConfig = params?.let { json("""{"search": {"params": $it}}""") }
                service.generate(namespaceId, userId, request(baseFlowConfiguration = baseConfig))
                return message.captured
            }

            val line = "Recipient of each case (its relation to the entity of the case):"
            plannerMessageFor("""{"resolveTargets": ["MANAGER"]}""") shouldContain "$line MANAGER"
            plannerMessageFor("""{"resolveTargets": ["OWNER", "MANAGER", "OWNER"]}""") shouldContain "$line OWNER, MANAGER"
            forAll(
                row(null),
                row("""{"agentConfigId": "x"}"""),
                row("""{"resolveTargets": []}"""),
                row("""{"resolveTargets": "MANAGER"}"""),
                row("""{"resolveTargets": [1]}"""),
            ) { params ->
                plannerMessageFor(params) shouldNotContain "Recipient of each case"
            }
        }

        "a malformed base part is a 400 even when the request field overrides it" {
            shouldThrow<BadRequestException> {
                service.generate(
                    namespaceId,
                    userId,
                    request(
                        searchToolName = "searchAgentUsers",
                        baseFlowConfiguration = json("""{"search": {"tool": 42}}"""),
                    ),
                )
            }
        }

        "the request validation refuses a base that is not an object, or too large" {
            forAll(
                row(json("[1, 2]")),
                row(json("\"text\"")),
                row(
                    json(
                        """{"search": {"params": {"x": "${
                            "y".repeat(
                                ScheduledPromptGenerationRequest.MAX_BASE_FLOW_CONFIGURATION_LENGTH,
                            )
                        }"}}}""",
                    ),
                ),
            ) { node ->
                validator
                    .validate(request(baseFlowConfiguration = node))
                    .map { it.message }
                    .joinToString() shouldContain
                    "baseFlowConfiguration"
            }
            validator.validate(request(baseFlowConfiguration = base)) shouldBe emptySet()
            validator.validate(request(baseFlowConfiguration = NullNode.instance)) shouldBe emptySet()
        }

        // -------------------------------------------------------------------------
        // Flow configuration presets
        // -------------------------------------------------------------------------

        val stories =
            FlowConfigurationPreset(
                "Stories",
                json(
                    """
                    {
                      "search": { "tool": "searchAgentUsers", "params": { "agentConfigId": "stories-agent" } },
                      "act": { "agentName": "Luigi" }
                    }
                    """.trimIndent(),
                ),
            )
        val bench =
            FlowConfigurationPreset(
                "Bench",
                json("""{"search": {"tool": "searchBench", "params": {"scope": "bench"}}, "act": {"agentName": "Mario"}}"""),
            )

        fun presetChosen(
            preset: FlowConfigurationPreset?,
            rationale: String? = null,
            warnings: List<String> = emptyList(),
            unusableRawText: String? = null,
        ) {
            coEvery { presetSelector.choose(namespaceId, userId, any(), any(), any()) } returns
                FlowConfigurationPresetSelector.PresetOutcome(
                    preset = preset,
                    decision =
                        preset?.let {
                            GeneratedFieldDecision(
                                GeneratedField.PRESET,
                                GeneratedFieldSource.INFERRED,
                                it.name,
                                rationale,
                            )
                        },
                    warnings = warnings,
                    unusableRawText = unusableRawText,
                )
        }

        "a base flow configuration wins: the presets are ignored, no preset is chosen" {
            val result =
                service.generate(
                    namespaceId,
                    userId,
                    request(baseFlowConfiguration = base, flowConfigurationPresets = listOf(stories, bench)),
                )

            coVerify(exactly = 0) { presetSelector.choose(any(), any(), any(), any(), any()) }
            result.chosenPreset.shouldBeNull()
            result.decisions.none { it.field == GeneratedField.PRESET } shouldBe true
            coVerify {
                loopConfigGenerationService.generate(
                    any(),
                    "searchAgentUsers",
                    any(),
                    any(),
                    base["search"]["params"],
                )
            }
        }

        "no presets, null or empty: no selection" {
            service.generate(namespaceId, userId, request(flowConfigurationPresets = null)).chosenPreset.shouldBeNull()
            service
                .generate(
                    namespaceId,
                    userId,
                    request(flowConfigurationPresets = emptyList()),
                ).chosenPreset
                .shouldBeNull()

            coVerify(exactly = 0) { presetSelector.choose(any(), any(), any(), any(), any()) }
        }

        "the chosen preset acts as the base flow configuration, and is reported" {
            every { definition.systemPrompt } returns "Lexicon: a talent request is a task of type MISSION."
            presetChosen(stories, rationale = "stories")

            val result =
                service.generate(namespaceId, userId, request(flowConfigurationPresets = listOf(stories, bench)))

            // the selector gets the loop agent's system prompt, for its lexical definitions
            coVerify {
                presetSelector.choose(
                    namespaceId = namespaceId,
                    userId = userId,
                    description = any(),
                    presets = listOf(stories, bench),
                    agentSystemPrompt = "Lexicon: a talent request is a task of type MISSION.",
                )
            }
            coVerify { agentService.resolveDefinition(loopB.metadata.id, namespaceId, userId) }
            result.chosenPreset shouldBe "Stories"
            result.decisions.first().let {
                it.field shouldBe GeneratedField.PRESET
                it.value shouldBe "Stories"
                it.rationale shouldBe "stories"
            }
            // the preset's search.tool and params, and act.agentName, as with a base flow configuration
            coVerify {
                loopConfigGenerationService.generate(
                    definition,
                    "searchAgentUsers",
                    any(),
                    any(),
                    stories.baseFlowConfiguration["search"]["params"],
                )
            }
            result.decisions.first { it.field == GeneratedField.SEARCH }.source shouldBe GeneratedFieldSource.PROVIDED
            result.decisions.first { it.field == GeneratedField.TARGET_AGENT }.source shouldBe GeneratedFieldSource.PROVIDED
            result.scheduledPrompt
                .shouldNotBeNull()
                .flowConfiguration!!["act"]["agentName"]
                .asText() shouldBe "Luigi"
        }

        "another chosen preset gives another base" {
            presetChosen(bench)

            val result =
                service.generate(namespaceId, userId, request(flowConfigurationPresets = listOf(stories, bench)))

            result.chosenPreset shouldBe "Bench"
            coVerify {
                loopConfigGenerationService.generate(
                    any(),
                    "searchBench",
                    any(),
                    any(),
                    bench.baseFlowConfiguration["search"]["params"],
                )
            }
            result.scheduledPrompt
                .shouldNotBeNull()
                .flowConfiguration!!["act"]["agentName"]
                .asText() shouldBe "Mario"
        }

        "the request's searchToolName wins over the chosen preset's search.tool" {
            presetChosen(stories)

            service.generate(
                namespaceId,
                userId,
                request(searchToolName = "otherTool", flowConfigurationPresets = listOf(stories)),
            )

            coVerify {
                loopConfigGenerationService.generate(
                    any(),
                    "otherTool",
                    any(),
                    any(),
                    stories.baseFlowConfiguration["search"]["params"],
                )
            }
            // a single preset needs no LLM call, so no system prompt either
            coVerify { presetSelector.choose(namespaceId, userId, any(), listOf(stories), isNull()) }
        }

        "no usable preset choice: its warnings, no preset, and the generation goes on without a base" {
            presetChosen(null, warnings = listOf("The LLM chose preset 'Ghost'.", "Continuing without a preset."))

            val result =
                service.generate(namespaceId, userId, request(flowConfigurationPresets = listOf(stories, bench)))

            result.chosenPreset.shouldBeNull()
            result.decisions.none { it.field == GeneratedField.PRESET } shouldBe true
            result.warnings.take(2) shouldContainExactly
                listOf(
                    "The LLM chose preset 'Ghost'.",
                    "Continuing without a preset.",
                )
            result.scheduledPrompt.shouldNotBeNull()
            coVerify { loopConfigGenerationService.generate(any(), null, any(), any(), isNull()) }
        }

        "an unusable preset answer is the raw response when there is no proposal, never with one" {
            presetChosen(null, unusableRawText = "preset raw")
            service
                .generate(
                    namespaceId,
                    userId,
                    request(flowConfigurationPresets = listOf(stories, bench)),
                ).rawResponse
                .shouldBeNull()

            coEvery {
                structuredLlmClient.call(
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returns planAnswer(mapOf("targetAgentName" to "Ghost"))
            val noProposal =
                service.generate(namespaceId, userId, request(flowConfigurationPresets = listOf(stories, bench)))
            noProposal.scheduledPrompt.shouldBeNull()
            noProposal.rawResponse shouldBe "preset raw"
        }

        "a preset naming an unavailable target agent is a 400 naming the preset, before any LLM call, whichever the LLM would pick" {
            val ghost = FlowConfigurationPreset("Ghostly", json("""{"act": {"agentName": "Ghost"}}"""))

            val message =
                shouldThrow<BadRequestException> {
                    service.generate(namespaceId, userId, request(flowConfigurationPresets = listOf(stories, ghost)))
                }.message

            message shouldContain "Ghost"
            message shouldContain "flowConfigurationPresets['Ghostly'].baseFlowConfiguration.act.agentName"
            message shouldContain "Luigi"
            coVerify(exactly = 0) { presetSelector.choose(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { structuredLlmClient.call(any(), any(), any(), any()) }
        }

        "a preset's target agent is not checked when the request names the target agent, which wins over it" {
            val ghost = FlowConfigurationPreset("Ghostly", json("""{"act": {"agentName": "Ghost"}}"""))
            presetChosen(ghost)

            val result =
                service.generate(
                    namespaceId,
                    userId,
                    request(targetAgentName = "Mario", flowConfigurationPresets = listOf(stories, ghost)),
                )

            result.scheduledPrompt
                .shouldNotBeNull()
                .flowConfiguration!!["act"]["agentName"]
                .asText() shouldBe "Mario"
        }

        "a preset's target agent is not checked when a base flow configuration makes the presets useless" {
            val ghost = FlowConfigurationPreset("Ghostly", json("""{"act": {"agentName": "Ghost"}}"""))

            service
                .generate(
                    namespaceId,
                    userId,
                    request(baseFlowConfiguration = base, flowConfigurationPresets = listOf(ghost)),
                ).scheduledPrompt
                .shouldNotBeNull()
        }

        "a namespace that cannot host a loop, or an unknown requested agent, fails before the preset LLM call" {
            forAll(
                row(listOf(luigi, mario), request(flowConfigurationPresets = listOf(stories, bench))),
                row(
                    listOf(loopB, luigi),
                    request(loopAgentName = "Ghost", flowConfigurationPresets = listOf(stories, bench)),
                ),
                row(
                    listOf(loopB, luigi),
                    request(targetAgentName = "Ghost", flowConfigurationPresets = listOf(stories, bench)),
                ),
            ) { agents, req ->
                every { agentConfigService.findByNamespace(namespaceId, false) } returns agents
                runCatching { service.generate(namespaceId, userId, req) }.isFailure shouldBe true
            }
            coVerify(exactly = 0) { presetSelector.choose(any(), any(), any(), any(), any()) }
        }

        "a malformed preset is a 400 naming it, before any LLM call, whichever the LLM would pick" {
            val bad = FlowConfigurationPreset("Bad", json("""{"search": {"tool": 42}}"""))

            val message =
                shouldThrow<BadRequestException> {
                    service.generate(namespaceId, userId, request(flowConfigurationPresets = listOf(stories, bad)))
                }.message

            message shouldContain "flowConfigurationPresets['Bad'].baseFlowConfiguration.search.tool"
            coVerify(exactly = 0) { structuredLlmClient.call(any(), any(), any(), any()) }
            coVerify(exactly = 0) { presetSelector.choose(any(), any(), any(), any(), any()) }
        }

        "a malformed preset is a 400 even when a base flow configuration makes the presets useless" {
            val bad = FlowConfigurationPreset("Bad", json("""{"act": {"agentName": {"x": 1}}}"""))

            shouldThrow<BadRequestException> {
                service.generate(
                    namespaceId = namespaceId,
                    userId = userId,
                    request = request(baseFlowConfiguration = base, flowConfigurationPresets = listOf(bad)),
                )
            }.message shouldContain "flowConfigurationPresets['Bad'].baseFlowConfiguration.act.agentName"
        }

        "the request validation refuses duplicate preset names, too many presets, and a preset config that is no object" {
            fun violations(presets: List<FlowConfigurationPreset>) =
                validator.validate(request(flowConfigurationPresets = presets)).map { it.message }.joinToString()

            violations(listOf(stories, stories.copy(name = "STORIES"))) shouldContain "unique names"
            violations(List(ScheduledPromptGenerationRequest.MAX_PRESETS + 1) { stories.copy(name = "p$it") }) shouldContain
                "at most ${ScheduledPromptGenerationRequest.MAX_PRESETS} presets"
            violations(listOf(FlowConfigurationPreset("Array", json("[1, 2]")))) shouldContain "must be a JSON object"
            violations(
                listOf(
                    FlowConfigurationPreset(
                        " ",
                        stories.baseFlowConfiguration,
                    ),
                ),
            ) shouldContain "name must not be blank"
            // a null element: a violation, not an exception
            @Suppress("UNCHECKED_CAST")
            violations(listOf(stories, null) as List<FlowConfigurationPreset>) shouldContain "no null element"
            // names that differ only by case are duplicates, whatever the default locale
            violations(listOf(stories.copy(name = "title"), stories.copy(name = "TITLE"))) shouldContain "unique names"
            // each one within its own bound, too heavy together
            val heavy =
                List(4) {
                    FlowConfigurationPreset(
                        "heavy$it",
                        json("""{"search": {"params": {"x": "${"y".repeat(8_000)}"}}}"""),
                    )
                }
            violations(heavy) shouldContain "weigh at most"
            validator.validate(request(flowConfigurationPresets = listOf(stories, bench))) shouldBe emptySet()
        }

        // -------------------------------------------------------------------------
        // Refusals
        // -------------------------------------------------------------------------

        "an unknown provided agent is a 400 naming the available ones" {
            forAll(
                row(request(loopAgentName = "Ghost"), "LoopB"),
                row(request(targetAgentName = "Ghost"), "Luigi"),
                // a LOOP agent is not a target, nor a target a loop agent
                row(request(targetAgentName = "LoopB"), "Mario"),
                row(request(loopAgentName = "Luigi"), "LoopB"),
            ) { req, available ->
                shouldThrow<BadRequestException> {
                    service.generate(
                        namespaceId = namespaceId,
                        userId = userId,
                        request = req,
                    )
                }.message shouldContain available
            }
        }

        "no LOOP agent, several LOOP agents, or no target agent in the namespace is a 422, before any LLM call" {
            forAll(
                row(listOf(luigi, mario), "No LOOP agent"),
                row(listOf(loopA, loopB, luigi), "exactly one"),
                row(listOf(loopB), "No agent"),
            ) { agents, expected ->
                every { agentConfigService.findByNamespace(namespaceId, false) } returns agents
                shouldThrow<UnprocessableEntityException> {
                    service.generate(namespaceId, userId, request())
                }.message shouldContain expected
            }
            coVerify(exactly = 0) { structuredLlmClient.call(any(), any(), any(), any()) }
        }

        // -------------------------------------------------------------------------
        // Unusable LLM answers
        // -------------------------------------------------------------------------

        "an LLM choice outside the candidates, or a missing one, yields no proposal and a warning" {
            forAll(
                row(mapOf<String, Any?>("targetAgentName" to "Ghost"), "Ghost"),
                row(mapOf<String, Any?>("targetAgentName" to "LoopB"), "LoopB"),
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
            coEvery { structuredLlmClient.call(any(), any(), any(), any()) } returns
                StructuredLlmResult(
                    json = null,
                    rawText = "sorry",
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

            result.scheduledPrompt.shouldNotBeNull().planning shouldBe
                PlanningDto(
                    startDate = today,
                    endType = SchedulerEndType.NEVER,
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
                    model = any(),
                    provider = any(),
                    systemPrompt = any(),
                    userMessage = any(),
                )
            } returns planAnswer(mapOf("targetAgentName" to "Ghost"))

            val result = service.generate(namespaceId, userId, request())

            result.summary.shouldBeNull()
            coVerify(exactly = 0) { summaryService.summarize(any(), any(), any(), any()) }
        }

        "a failed summary keeps the proposal, with the summary's warning" {
            coEvery { summaryService.summarize(any(), any(), any(), any()) } returns
                ScheduledPromptSummaryService.SummaryOutcome(
                    summary = null,
                    warning = "No summary could be generated.",
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
