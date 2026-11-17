package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.aiModel.ModelResolver
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.generation.StructuredLlmClient
import io.whozoss.agentos.generation.StructuredLlmResult
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedField
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedFieldDecision
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedFieldSource
import io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.RecurrenceDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptGenerationRequest
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptGenerationResult
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.workflow.AgentLoopPayload
import io.whozoss.agentos.workflow.LoopConfigGenerationService
import io.whozoss.agentos.user.UserService
import jakarta.validation.Validator
import mu.KLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Builds a **proposal** of a scheduled prompt driving a LOOP agent from a natural-language description. Nothing is
 * persisted: the proposal is meant to be reviewed by a human, then POSTed to `POST /api/scheduled-prompts`. It is
 * proposed disabled, so that posting it unchanged never starts a schedule.
 *
 * Every field of [ScheduledPromptGenerationRequest] but the description is optional. A provided field always wins;
 * a missing one is decided by an LLM.
 *
 * ## Pipeline (at most three LLM calls)
 *
 * 1. [candidates], no LLM: the agents deployed to the caller, split into LOOP agents (persisted ones only: a
 *    filesystem agent cannot back a scheduled prompt) and target agents (every non-LOOP agent). A provided name that
 *    is not a candidate is a 400. No LOOP or no target candidate is a 422.
 * 2. [plan]: with the namespace default model ([ModelResolver]), the LLM proposes what is missing among the loop
 *    agent, the target agent, the prompt, the name, the recurrence and the planning. Skipped when nothing is missing
 *    but the name, which is then derived from the description.
 * 3. [resolveFields]: each field is resolved on its own, by the same rule: provided, else obvious (a single LOOP
 *    candidate), else the LLM's answer, checked against the candidates or by Bean Validation, else, for the planning
 *    only, a default ("from today, never ends").
 * 4. [search], only when every field is resolved: [LoopConfigGenerationService] generates the search block with the
 *    loop agent's own tools and model, given the target agent's name and id as context.
 * 5. [assemble]: `flowConfiguration = { search, act: { agentName } }`, without `act.prompt`, which the scheduler sets
 *    from `promptContent` at run time. The whole [ScheduledPromptDto] goes through Bean Validation, and the
 *    `flowConfiguration` is checked to parse as an [AgentLoopPayload] once that prompt is set. A name already taken
 *    by a namespace-shared scheduled prompt ([ScheduledPromptService.isNameTaken]) is a warning:
 *    `POST /api/scheduled-prompts` would answer 409 until it is renamed.
 * 6. [summary], only when there is a proposal: [ScheduledPromptSummaryService] writes the end-user summary from the
 *    final values, in the caller's preferred language. A failed summary keeps the proposal, with a warning.
 *
 * Each step returns a value describing its outcome (warnings included) rather than stopping the method: a part that
 * cannot be determined yields a null proposal and warnings saying why. LLM exceptions are not caught.
 *
 * ## rawResponse
 *
 * The raw text of an LLM answer is returned only when there is no proposal, and only the one of the step that could
 * not be used: the search answer when it was unusable, else the planning answer when it was unusable. A returned
 * proposal never comes with a raw response.
 *
 * ## Known limitation
 *
 * The scheduler fails every run of a LOOP agent reaching more than one user (`ScheduledPromptExecutor.materialize`).
 * The candidates are not filtered on that: the only reusable count of an agent's users
 * (`UserGroupRepository.findUsersByAgentId`) ignores direct namespace deployments, so it would wrongly discard or
 * keep agents. TODO: filter LOOP candidates on a single reached user once a count matching `materialize` exists.
 */
@Service
class ScheduledPromptGenerationService(
    private val agentConfigService: AgentConfigService,
    private val agentService: AgentService,
    private val modelResolver: ModelResolver,
    private val structuredLlmClient: StructuredLlmClient,
    private val loopConfigGenerationService: LoopConfigGenerationService,
    private val scheduledPromptService: ScheduledPromptService,
    private val summaryService: ScheduledPromptSummaryService,
    private val userService: UserService,
    private val objectMapper: ObjectMapper,
    private val validator: Validator,
    private val clock: Clock,
) {
    suspend fun generate(
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
    ): ScheduledPromptGenerationResult {
        val candidates = candidates(namespaceId, userId, request)
        val plan = plan(namespaceId, userId, request, candidates)
        val fields = resolveFields(request, candidates, plan)
        val complete = fields.complete()
        val search = complete?.let { search(it, namespaceId, userId, request) }
        val assembled = assemble(namespaceId, request, plan, fields, complete, search)
        val summary = summary(namespaceId, userId, request, assembled, complete, search)
        return assembled.copy(
            summary = summary?.summary,
            warnings = assembled.warnings + listOfNotNull(summary?.warning),
        )
    }

    // -------------------------------------------------------------------------
    // 1. Candidates
    // -------------------------------------------------------------------------

    private data class Candidates(
        val loops: List<AgentConfig>,
        val targets: List<AgentConfig>,
        val providedLoop: AgentConfig?,
        val providedTarget: AgentConfig?,
    ) {
        /** The loop agent needs no choice when it is provided or when there is a single candidate. */
        val obviousLoop: AgentConfig? get() = providedLoop ?: loops.singleOrNull()
    }

    /** Throws [UnprocessableEntityException] (422) when a side has no candidate, [BadRequestException] (400) on an unknown name. */
    private fun candidates(
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
    ): Candidates {
        val accessible = withShadowing(agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null))
        val loops = accessible.filter { it.resolvedExecutionMode == ExecutionMode.LOOP && !it.isFilesystemOnly }
        val targets = accessible.filter { it.resolvedExecutionMode != ExecutionMode.LOOP }
        when {
            loops.isEmpty() -> throw UnprocessableEntityException("No LOOP agent available to you in this namespace: a scheduled loop needs one.")
            targets.isEmpty() -> throw UnprocessableEntityException("No agent available to you in this namespace to start for each search result.")
        }
        return Candidates(
            loops = loops,
            targets = targets,
            providedLoop = request.loopAgentName?.let { pick(loops, it, "LOOP agent") },
            providedTarget = request.targetAgentName?.let { pick(targets, it, "target agent") },
        )
    }

    /** Namespace-scoped agents shadow platform agents of the same name, as in `AgentServiceImpl`. */
    private fun withShadowing(agents: List<AgentConfig>): List<AgentConfig> =
        agents
            .groupBy { it.name.lowercase() }
            .map { (_, sameName) -> sameName.firstOrNull { it.namespaceId != null } ?: sameName.first() }
            .sortedBy { it.name.lowercase() }

    private fun pick(
        candidates: List<AgentConfig>,
        name: String,
        label: String,
    ): AgentConfig =
        candidates.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw BadRequestException("Unknown $label '$name'. Available: ${candidates.map { it.name }}")

    // -------------------------------------------------------------------------
    // 2. Planning call
    // -------------------------------------------------------------------------

    /** The planning answer, or the reason there is none; [skipped] when nothing needed planning. */
    private data class PlanOutcome(
        val json: JsonNode? = null,
        val rawText: String? = null,
        val warnings: List<String> = emptyList(),
        val skipped: Boolean = false,
    ) {
        val unusable: Boolean get() = !skipped && json == null

        /** The raw text of the answer, when it could not be used; null otherwise. */
        val unusableRawText: String? get() = rawText.takeIf { unusable }

        fun text(key: String): String? = json?.get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

        fun rationale(field: GeneratedField): String? =
            json
                ?.get("rationale")
                ?.get(RATIONALE_KEYS.getValue(field))
                ?.takeIf { it.isTextual }
                ?.asText()
                ?.takeIf { it.isNotBlank() }
    }

    private suspend fun plan(
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
        candidates: Candidates,
    ): PlanOutcome {
        val needed =
            candidates.obviousLoop == null ||
                candidates.providedTarget == null ||
                request.prompt.isNullOrBlank() ||
                request.recurrence == null ||
                request.planning == null
        return when {
            needed -> planOutcome(callPlanner(namespaceId, userId, request, candidates))
            else -> PlanOutcome(skipped = true)
        }
    }

    private fun planOutcome(llm: StructuredLlmResult): PlanOutcome =
        PlanOutcome(
            json = llm.json,
            rawText = llm.rawText,
            warnings =
                listOfNotNull(
                    "The LLM returned no content.".takeIf { llm.returnedNothing },
                    "The LLM response could not be parsed as a JSON object.".takeIf { llm.json == null && !llm.returnedNothing },
                    "JSON was extracted from a markdown code fence in the LLM response.".takeIf { llm.usedCodeFence },
                ),
        )

    private suspend fun callPlanner(
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
        candidates: Candidates,
    ): StructuredLlmResult {
        val (model, provider) =
            modelResolver.resolve(namespaceId, userId)
                ?: throw UnprocessableEntityException("No AI model is configured for namespace $namespaceId.")
        return structuredLlmClient.call(model, provider, PLANNING_SYSTEM_PROMPT, plannerMessage(request, candidates))
    }

    private fun plannerMessage(
        request: ScheduledPromptGenerationRequest,
        candidates: Candidates,
    ): String {
        val fixed =
            listOfNotNull(
                candidates.obviousLoop?.let { "loopAgentName" to it.name },
                candidates.providedTarget?.let { "targetAgentName" to it.name },
                request.prompt?.takeIf { it.isNotBlank() }?.let { "prompt" to it },
                request.recurrence?.let { "recurrence" to objectMapper.writeValueAsString(it) },
                request.planning?.let { "planning" to objectMapper.writeValueAsString(it) },
            )
        return buildString {
            appendLine("Today (UTC): ${LocalDate.now(clock)}")
            appendLine()
            appendLine("LOOP agent candidates (the agent running the automation):")
            candidates.loops.forEach { appendLine("- ${it.name}: ${it.description.orEmpty()}") }
            appendLine()
            appendLine("Target agent candidates (the agent started for each search result):")
            candidates.targets.forEach { appendLine("- ${it.name}: ${it.description.orEmpty()}") }
            if (fixed.isNotEmpty()) {
                appendLine()
                appendLine("Already decided (copy these values as is):")
                fixed.forEach { (key, value) -> appendLine("- $key: $value") }
            }
            appendLine()
            appendLine("User description:")
            append(request.description)
        }
    }

    // -------------------------------------------------------------------------
    // 3. Field resolution
    // -------------------------------------------------------------------------

    /** One field: its value (null when undetermined), how it was obtained, and an optional warning. */
    private data class FieldOutcome<T : Any>(
        val value: T?,
        val decision: GeneratedFieldDecision?,
        val warning: String? = null,
    )

    private data class ResolvedFields(
        val loopAgent: FieldOutcome<AgentConfig>,
        val targetAgent: FieldOutcome<AgentConfig>,
        val prompt: FieldOutcome<String>,
        val name: FieldOutcome<String>,
        val recurrence: FieldOutcome<RecurrenceDto>,
        val planning: FieldOutcome<PlanningDto>,
    ) {
        private val all: List<FieldOutcome<*>> get() = listOf(loopAgent, targetAgent, prompt, name, recurrence, planning)
        val decisions: List<GeneratedFieldDecision> get() = all.mapNotNull { it.decision }
        val warnings: List<String> get() = all.mapNotNull { it.warning }

        /** Every value, or null when one of them is undetermined. */
        fun complete(): CompleteFields? {
            val loop = loopAgent.value
            val target = targetAgent.value
            val promptValue = prompt.value
            val nameValue = name.value
            val recurrenceValue = recurrence.value
            val planningValue = planning.value
            return when {
                loop == null || target == null || promptValue == null || nameValue == null ||
                    recurrenceValue == null || planningValue == null -> null
                else -> CompleteFields(loop, target, promptValue, nameValue, recurrenceValue, planningValue)
            }
        }
    }

    private data class CompleteFields(
        val loopAgent: AgentConfig,
        val targetAgent: AgentConfig,
        val prompt: String,
        val name: String,
        val recurrence: RecurrenceDto,
        val planning: PlanningDto,
    )

    private fun resolveFields(
        request: ScheduledPromptGenerationRequest,
        candidates: Candidates,
        plan: PlanOutcome,
    ) = ResolvedFields(
        loopAgent =
            resolveAgent(GeneratedField.LOOP_AGENT, candidates.providedLoop, candidates.loops.singleOrNull(), plan, "loopAgentName", candidates.loops),
        targetAgent = resolveAgent(GeneratedField.TARGET_AGENT, candidates.providedTarget, null, plan, "targetAgentName", candidates.targets),
        prompt = resolvePrompt(request.prompt, plan),
        name = resolveName(request.description, plan),
        recurrence = resolveRecurrence(request.recurrence, plan),
        planning = resolvePlanning(request.planning, plan),
    )

    /**
     * The provided agent, else the [obvious] one (a single candidate), else the one the LLM chose under [planKey],
     * which must be among [candidates].
     */
    private fun resolveAgent(
        field: GeneratedField,
        provided: AgentConfig?,
        obvious: AgentConfig?,
        plan: PlanOutcome,
        planKey: String,
        candidates: List<AgentConfig>,
    ): FieldOutcome<AgentConfig> {
        val chosenName = plan.text(planKey)
        val chosen = chosenName?.let { name -> candidates.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        return when {
            provided != null -> FieldOutcome(provided, providedDecision(field, provided.name))
            obvious != null -> FieldOutcome(obvious, inferredDecision(field, obvious.name, ONLY_CANDIDATE_RATIONALE))
            chosen != null -> FieldOutcome(chosen, inferredDecision(field, chosen.name, plan.rationale(field)))
            chosenName != null ->
                undetermined<AgentConfig>(
                    "The LLM chose '$chosenName' as ${label(field)}, which is not among the available agents ${candidates.map { it.name }}.",
                )
            else -> undetermined<AgentConfig>("The LLM did not choose a ${label(field)}.".takeUnless { plan.unusable })
        }
    }

    private fun resolvePrompt(
        provided: String?,
        plan: PlanOutcome,
    ): FieldOutcome<String> {
        val proposed = plan.text("prompt")
        return when {
            !provided.isNullOrBlank() -> FieldOutcome(provided, providedDecision(GeneratedField.PROMPT, null))
            proposed != null -> FieldOutcome(proposed, inferredDecision(GeneratedField.PROMPT, null, plan.rationale(GeneratedField.PROMPT)))
            else -> undetermined<String>("The LLM did not propose a prompt for the started cases.".takeUnless { plan.unusable })
        }
    }

    /** The LLM's name, else the first line of the description; cut to [MAX_GENERATED_NAME_LENGTH] either way. */
    private fun resolveName(
        description: String,
        plan: PlanOutcome,
    ): FieldOutcome<String> {
        val proposed = plan.text("name")?.take(MAX_GENERATED_NAME_LENGTH)
        return when (proposed) {
            null -> {
                val derived = description.trim().lineSequence().first().take(MAX_GENERATED_NAME_LENGTH)
                FieldOutcome(derived, inferredDecision(GeneratedField.NAME, derived, "derived from the description"))
            }
            else -> FieldOutcome(proposed, inferredDecision(GeneratedField.NAME, proposed, plan.rationale(GeneratedField.NAME)))
        }
    }

    private fun resolveRecurrence(
        provided: RecurrenceDto?,
        plan: PlanOutcome,
    ): FieldOutcome<RecurrenceDto> {
        val (proposed, problem) = parseValid(plan.json?.get("recurrence"), RecurrenceDto::class.java)
        return when {
            provided != null -> FieldOutcome(provided, providedDecision(GeneratedField.RECURRENCE, null))
            proposed != null ->
                FieldOutcome(proposed, inferredDecision(GeneratedField.RECURRENCE, null, plan.rationale(GeneratedField.RECURRENCE)))
            else -> undetermined<RecurrenceDto>(problem?.let { "The recurrence proposed by the LLM $it." }.takeUnless { plan.unusable })
        }
    }

    private fun resolvePlanning(
        provided: PlanningDto?,
        plan: PlanOutcome,
    ): FieldOutcome<PlanningDto> {
        val (proposed, problem) = parseValid(plan.json?.get("planning"), PlanningDto::class.java)
        return when {
            provided != null -> FieldOutcome(provided, providedDecision(GeneratedField.PLANNING, null))
            proposed != null ->
                FieldOutcome(proposed, inferredDecision(GeneratedField.PLANNING, null, plan.rationale(GeneratedField.PLANNING)))
            else ->
                FieldOutcome(
                    PlanningDto(startDate = LocalDate.now(clock), endType = SchedulerEndType.NEVER),
                    inferredDecision(GeneratedField.PLANNING, null, "default"),
                    listOfNotNull("The planning proposed by the LLM ${problem ?: "is missing"}.", "Planning defaulted to: from today, never ends.")
                        .joinToString(" "),
                )
        }
    }

    /**
     * Parses [node] as a [type] and runs Bean Validation on it. Returns the value, or the problem (absent, unreadable,
     * invalid) as a sentence fragment.
     */
    private fun <T : Any> parseValid(
        node: JsonNode?,
        type: Class<T>,
    ): Pair<T?, String?> {
        val parsed = node?.takeUnless { it.isNull }?.let { present -> runCatching { objectMapper.treeToValue(present, type) } }
        val violations = parsed?.getOrNull()?.let { validator.validate(it) }.orEmpty()
        return when {
            parsed == null -> null to "is missing"
            parsed.isFailure -> null to "could not be read: ${parsed.exceptionOrNull()?.message}"
            violations.isNotEmpty() -> null to "is invalid: ${violations.joinToString("; ") { it.message }}"
            else -> parsed.getOrNull() to null
        }
    }

    private fun <T : Any> undetermined(warning: String?) = FieldOutcome<T>(value = null, decision = null, warning = warning)

    private fun providedDecision(
        field: GeneratedField,
        value: String?,
    ) = GeneratedFieldDecision(field = field, source = GeneratedFieldSource.PROVIDED, value = value)

    private fun inferredDecision(
        field: GeneratedField,
        value: String?,
        rationale: String?,
    ) = GeneratedFieldDecision(field = field, source = GeneratedFieldSource.INFERRED, value = value, rationale = rationale)

    private fun label(field: GeneratedField): String =
        when (field) {
            GeneratedField.LOOP_AGENT -> "LOOP agent"
            GeneratedField.TARGET_AGENT -> "target agent"
            else -> field.name.lowercase()
        }

    // -------------------------------------------------------------------------
    // 4. Search call
    // -------------------------------------------------------------------------

    private data class SearchOutcome(
        val block: JsonNode?,
        val decision: GeneratedFieldDecision?,
        val warning: String?,
        /** The raw text of the search answer, set only when it could not be used. */
        val unusableRawText: String?,
        /** What the chosen search tool does, as the tool describes itself; used by the summary. */
        val toolDescription: String?,
    )

    private suspend fun search(
        fields: CompleteFields,
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
    ): SearchOutcome {
        val definition = agentService.resolveDefinition(fields.loopAgent.metadata.id, namespaceId, userId)
        val result =
            loopConfigGenerationService.generate(
                definition = definition,
                searchToolName = request.searchToolName,
                description = request.description,
                context =
                    "Target agent: ${fields.targetAgent.name} (agentConfigId=${fields.targetAgent.metadata.id}), " +
                        "the agent started for each search result.",
            )
        val block = result.loopConfig?.get("search")
        val source = if (request.searchToolName != null) GeneratedFieldSource.PROVIDED else GeneratedFieldSource.INFERRED
        return SearchOutcome(
            block = block,
            decision = block?.let { GeneratedFieldDecision(field = GeneratedField.SEARCH, source = source, value = it.get("tool")?.asText()) },
            warning = result.warning,
            unusableRawText = result.rawResponse,
            toolDescription =
                block?.get("tool")?.asText()?.let { tool -> definition.tools.firstOrNull { it.name == tool }?.description },
        )
    }

    // -------------------------------------------------------------------------
    // 6. Summary
    // -------------------------------------------------------------------------

    /** The end-user summary of a returned proposal; null when there is no proposal (nothing to summarize). */
    private suspend fun summary(
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
        result: ScheduledPromptGenerationResult,
        complete: CompleteFields?,
        search: SearchOutcome?,
    ): ScheduledPromptSummaryService.SummaryOutcome? {
        val block = search?.block
        return when {
            result.scheduledPrompt == null || complete == null || block == null -> {
                null
            }

            else -> {
                summaryService.summarize(
                    namespaceId = namespaceId,
                    userId = userId,
                    input =
                        ScheduledPromptSummaryService.SummaryInput(
                            description = request.description,
                            recurrence = complete.recurrence,
                            planning = complete.planning,
                            targetAgentName = complete.targetAgent.name,
                            targetAgentDescription = complete.targetAgent.description,
                            prompt = complete.prompt,
                            searchTool = block.get("tool")?.asText().orEmpty(),
                            searchToolDescription = search?.toolDescription,
                            searchParams = block.get("params"),
                        ),
                    language = userService.findById(userId)?.preferredLanguage?.takeIf { it.isNotBlank() },
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // 5. Assembly
    // -------------------------------------------------------------------------

    private fun assemble(
        namespaceId: UUID,
        request: ScheduledPromptGenerationRequest,
        plan: PlanOutcome,
        fields: ResolvedFields,
        complete: CompleteFields?,
        search: SearchOutcome?,
    ): ScheduledPromptGenerationResult {
        val searchBlock = search?.block
        val (proposal, problems) =
            when {
                complete == null || searchBlock == null -> {
                    null to emptyList()
                }

                else -> {
                    val draft = proposal(namespaceId, request, complete, searchBlock)
                    draft to validationProblems(namespaceId, draft, complete, searchBlock)
                }
            }
        val valid = proposal?.takeIf { problems.none { it.blocking } }
        if (valid != null && complete != null) {
            logger.info {
                "[ScheduledPromptGeneration] Proposal for namespace=$namespaceId: loop='${complete.loopAgent.name}', " +
                    "target='${complete.targetAgent.name}', tool=${searchBlock?.get("tool")?.asText()}"
            }
        }
        return ScheduledPromptGenerationResult(
            scheduledPrompt = valid,
            decisions = fields.decisions + listOfNotNull(search?.decision),
            warnings = plan.warnings + fields.warnings + listOfNotNull(search?.warning) + problems.map { it.message },
            rawResponse =
                when (valid) {
                    null -> search?.unusableRawText ?: plan.unusableRawText
                    else -> null
                },
        )
    }

    private fun proposal(
        namespaceId: UUID,
        request: ScheduledPromptGenerationRequest,
        fields: CompleteFields,
        searchBlock: JsonNode,
    ) = ScheduledPromptDto(
        namespaceId = namespaceId,
        agentConfigId = fields.loopAgent.metadata.id,
        promptContent = fields.prompt,
        name = fields.name,
        description = request.description,
        recurrence = fields.recurrence,
        planning = fields.planning,
        // Never live on creation: a proposal posted unchanged must not start messaging users.
        enabled = false,
        flowConfiguration = flowConfiguration(searchBlock, fields.targetAgent.name, prompt = null),
    )

    /** `{ search, act: { agentName[, prompt] } }`: without [prompt] as proposed, with it as the scheduler runs it. */
    private fun flowConfiguration(
        searchBlock: JsonNode,
        targetAgentName: String,
        prompt: String?,
    ): ObjectNode =
        objectMapper.createObjectNode().apply {
            set<JsonNode>("search", searchBlock.deepCopy())
            putObject("act").apply {
                put("agentName", targetAgentName)
                prompt?.let { put("prompt", it) }
            }
        }

    private data class ValidationProblem(
        val message: String,
        val blocking: Boolean,
    )

    /**
     * - Bean Validation of the whole proposal: blocking, `POST /api/scheduled-prompts` would refuse it.
     * - The flowConfiguration as the scheduler runs it (with `act.prompt`) must parse as an [AgentLoopPayload]: a
     *   warning, the scheduled prompt can be created but its runs would abort.
     * - The name must be free in the namespace: a warning, renaming the proposal is enough.
     */
    private fun validationProblems(
        namespaceId: UUID,
        proposal: ScheduledPromptDto,
        fields: CompleteFields,
        searchBlock: JsonNode,
    ): List<ValidationProblem> {
        val violations =
            validator.validate(proposal).map {
                ValidationProblem("The proposal is invalid: ${it.propertyPath} ${it.message}.", blocking = true)
            }
        val atRunTime = flowConfiguration(searchBlock, fields.targetAgent.name, fields.prompt)
        val runnable =
            runCatching { objectMapper.treeToValue(atRunTime, AgentLoopPayload::class.java) }
                .exceptionOrNull()
                ?.let { ValidationProblem("The generated flowConfiguration will not run as a loop payload: ${it.message}", blocking = false) }
        val nameTaken =
            ValidationProblem(
                "A scheduled prompt named '${fields.name}' already exists in this namespace: rename the proposal before creating it.",
                blocking = false,
            ).takeIf { scheduledPromptService.isNameTaken(namespaceId, null, fields.name) }
        return violations + listOfNotNull(runnable, nameTaken)
    }

    companion object : KLogging() {
        /** Bound on a generated name: the planning prompt asks for it, and it is enforced here. */
        const val MAX_GENERATED_NAME_LENGTH = 80

        private const val ONLY_CANDIDATE_RATIONALE = "only LOOP agent available"

        /** Keys of the planning answer's `rationale` object, per field. */
        private val RATIONALE_KEYS =
            mapOf(
                GeneratedField.LOOP_AGENT to "loopAgent",
                GeneratedField.TARGET_AGENT to "targetAgent",
                GeneratedField.PROMPT to "prompt",
                GeneratedField.NAME to "name",
                GeneratedField.RECURRENCE to "recurrence",
                GeneratedField.PLANNING to "planning",
                GeneratedField.SEARCH to "search",
            )

        private val PLANNING_SYSTEM_PROMPT =
            """
            You configure a scheduled automation from a user's description.

            The automation runs on a schedule. Each time, a LOOP agent searches for entities, and starts one case per
            result with a target agent, sending it an opening prompt.

            Produce ONLY a JSON object in this exact format:
            {
              "loopAgentName": "<one of the LOOP agent candidates>",
              "targetAgentName": "<one of the target agent candidates>",
              "prompt": "<opening message sent to the target agent for each result>",
              "name": "<short name of the automation, max $MAX_GENERATED_NAME_LENGTH characters>",
              "recurrence": { "unit": "WEEK" | "MONTH", "days": ["MONDAY", ...], "timeUtc": "HH:mm" },
              "planning": { "startDate": "YYYY-MM-DD", "endType": "NEVER" | "ON_DATE" | "OCCURRENCES",
                            "endDate": "YYYY-MM-DD" or null, "maxOccurrenceCount": <integer> or null },
              "rationale": { "loopAgent": "...", "targetAgent": "...", "prompt": "...", "name": "...",
                             "recurrence": "...", "planning": "..." }
            }

            Rules:
            - Agent names MUST be copied exactly from the candidate lists. Never invent one.
            - Copy the "Already decided" values as is.
            - The prompt is addressed to the target agent, in the language of the description; it must not mention ids
              or the search: the entity of each result is given to the target agent separately.
            - Times are in UTC. "days" only applies to WEEK (empty list = same weekday as startDate).
            - endDate is required, and strictly after startDate, when endType is ON_DATE.
              maxOccurrenceCount is required, and positive, when endType is OCCURRENCES.
            - When the description says nothing about the start, start today; about the end, use NEVER.
            - Each rationale is one short sentence.
            - No explanation, no markdown, no code fences. Start your response with '{' and end it with '}'.
            """.trimIndent()
    }
}
