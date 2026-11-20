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
import io.whozoss.agentos.scheduledPrompt.FlowConfigurationPresetSelector.PresetOutcome
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
import io.whozoss.agentos.sdk.api.scheduledPrompt.FlowConfigurationPreset
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedField
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedFieldDecision
import io.whozoss.agentos.sdk.api.scheduledPrompt.GeneratedFieldSource
import io.whozoss.agentos.sdk.api.scheduledPrompt.PlanningDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.RecurrenceDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptDto
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptGenerationRequest
import io.whozoss.agentos.sdk.api.scheduledPrompt.ScheduledPromptGenerationResult
import io.whozoss.agentos.sdk.api.scheduledPrompt.SchedulerEndType
import io.whozoss.agentos.user.UserService
import io.whozoss.agentos.workflow.AgentLoopPayload
import io.whozoss.agentos.workflow.LoopConfigGenerationService
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
 * ## Pipeline (at most four LLM calls)
 *
 * Every deterministic check (agents, presets, base flow configuration) runs before the first LLM call: a request
 * bound to fail never pays for one.
 *
 * 1. [agents], no LLM: the agents of the **namespace**, not the ones deployed to the caller. Generating is a
 *    design act, authorized by Namespace WRITE (checked by the controller); the scheduled prompt then runs for the
 *    user the loop agent is deployed to, not for the designer. The namespace must hold exactly one LOOP agent
 *    (persisted ones only: a filesystem agent cannot back a scheduled prompt), which is the loop agent. The target
 *    agents are the other published agents of the namespace and of the platform, a namespace agent shadowing a
 *    platform one of the same name. A requested loop or target agent that is not a candidate is a 400. No LOOP
 *    agent, several LOOP agents, or no target agent is a 422.
 * 2. [preset], only when the request has presets and no base flow configuration: [FlowConfigurationPresetSelector]
 *    chooses one preset from the description (no LLM call when there is a single one), whose flow configuration is
 *    then the base flow configuration of the following steps. The LLM is given the loop agent's system prompt, for
 *    its lexical definitions of the description's domain terms. No usable choice is a warning, and the generation goes
 *    on without a base. Then [candidates] completes the agents with the target agent of the inputs.
 * 3. [plan]: with the namespace default model ([ModelResolver]), the LLM proposes what is missing among the loop
 *    agent, the target agent, the prompt, the name, the recurrence and the planning. Skipped when nothing is missing
 *    but the name, which is then derived from the description.
 * 4. [resolveFields]: each field is resolved on its own, by the same rule: provided, else obvious (a single LOOP
 *    candidate), else the LLM's answer, checked against the candidates or by Bean Validation, else, for the planning
 *    only, a default ("from today, never ends").
 * 5. [search], only when every field is resolved: [LoopConfigGenerationService] generates the search block with the
 *    loop agent's own tools and model, given the target agent's name and id as context.
 * 6. [assemble]: `flowConfiguration = { search, act: { agentName } }`, without `act.prompt`, which the scheduler sets
 *    from `promptContent` at run time. The whole [ScheduledPromptDto] goes through Bean Validation, and the
 *    `flowConfiguration` is checked to parse as an [AgentLoopPayload] once that prompt is set. A name already taken
 *    by a namespace-shared scheduled prompt ([ScheduledPromptService.isNameTaken]) is a warning:
 *    `POST /api/scheduled-prompts` would answer 409 until it is renamed.
 * 7. [summary], only when there is a proposal: [ScheduledPromptSummaryService] writes the end-user summary from the
 *    final values, in the caller's preferred language. A failed summary keeps the proposal, with a warning.
 *
 * Each step returns a value describing its outcome (warnings included) rather than stopping the method: a part that
 * cannot be determined yields a null proposal and warnings saying why. LLM exceptions are not caught.
 *
 * ## Base flow configuration
 *
 * [ScheduledPromptGenerationRequest.baseFlowConfiguration] is a configuration to start from, typically copied from an
 * existing loop: the generation only enriches it. Its parts count as provided values, an explicit request field
 * winning over each of them ([inputs]):
 * - `search.tool` is the search tool: no tool selection happens;
 * - `search.params` are the base parameters, which the generated ones keep as they are (see
 *   [LoopConfigGenerationService]): the LLM only adds what the description asks for, typically criteria;
 * - `act.agentName` is the target agent, `act.prompt` the prompt;
 * - `search.params`, when not empty, are also shown as is to the planner: when they say who each case is opened with,
 *   the prompt is written in that recipient's voice. They are read by the LLM only: their keys are the search tool's,
 *   unknown to this service. Without them, the prompt is written to read right for any recipient.
 *
 * A part of the wrong type is a 400, as is a target agent that is not available.
 *
 * ## Presets
 *
 * [ScheduledPromptGenerationRequest.flowConfigurationPresets] are alternative base flow configurations. They are used
 * only when the request has no base flow configuration: the chosen preset's configuration then **is** the base flow
 * configuration, with the same rules (see above), and the chosen name is returned as `chosenPreset`.
 *
 * The outcome must not depend on the LLM's pick, so every preset is checked before the LLM call:
 * - structurally, with the rules of the base flow configuration, even when the presets end up ignored;
 * - its `act.agentName`, when the presets are used and the request names no target agent, must be a target candidate.
 *
 * Either failure is a 400 naming the preset.
 *
 * ## rawResponse
 *
 * The raw text of an LLM answer is returned only when there is no proposal, and only the one of the step that could
 * not be used: the search answer when it was unusable, else the planning answer when it was unusable, else the preset
 * answer when it was unusable. A returned proposal never comes with a raw response.
 *
 * ## Known limitations
 *
 * The scheduler fails every run of a LOOP agent reaching more than one user (`ScheduledPromptExecutor.materialize`).
 * The generation does not check that the namespace's LOOP agent reaches a single user: the only reusable count of an
 * agent's users (`UserGroupRepository.findUsersByAgentId`) ignores direct namespace deployments, so it would wrongly
 * refuse or accept. TODO: check it once a count matching `materialize` exists.
 *
 * The search step resolves the loop agent's tools with the caller's overlays, not with those of the user the loop
 * runs for: the tool names and schemas are the same, only per-user credentials could differ.
 */
@Service
class ScheduledPromptGenerationService(
    private val agentConfigService: AgentConfigService,
    private val agentService: AgentService,
    private val modelResolver: ModelResolver,
    private val structuredLlmClient: StructuredLlmClient,
    private val presetSelector: FlowConfigurationPresetSelector,
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
        val agents = agents(namespaceId, request)
        val preset = preset(namespaceId, userId, request, agents)
        val base = request.baseFlowConfiguration?.takeUnless { it.isNull } ?: preset.preset?.baseFlowConfiguration
        val inputs = inputs(request, base)
        val candidates = candidates(agents, inputs)
        val plan = plan(namespaceId, userId, request, inputs, candidates)
        val fields = resolveFields(request, inputs, candidates, plan)
        val complete = fields.complete()
        val search =
            complete?.let {
                search(
                    fields = it,
                    namespaceId = namespaceId,
                    userId = userId,
                    request = request,
                    inputs = inputs,
                )
            }
        val assembled =
            assemble(
                namespaceId = namespaceId,
                request = request,
                preset = preset,
                plan = plan,
                fields = fields,
                complete = complete,
                search = search,
            )
        val summary =
            summary(
                namespaceId = namespaceId,
                userId = userId,
                request = request,
                result = assembled,
                complete = complete,
                search = search,
            )
        return assembled.copy(
            summary = summary?.summary,
            warnings = assembled.warnings + listOfNotNull(summary?.warning),
        )
    }

    // -------------------------------------------------------------------------
    // 2. Preset: the base flow configuration, when the request has none
    // -------------------------------------------------------------------------

    /**
     * The preset to use as the base flow configuration: none (an empty outcome) when the request has a base flow
     * configuration or no presets. Every preset is checked before the LLM call (see "Presets" in the class KDoc), so
     * that the outcome does not depend on the LLM's pick.
     */
    private suspend fun preset(
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
        agents: Agents,
    ): PresetOutcome {
        val presets = request.flowConfigurationPresets.orEmpty()
        val parsed = presets.map { it to parseBase(it.baseFlowConfiguration, presetLabel(it)) }
        val used = presets.isNotEmpty() && request.baseFlowConfiguration?.takeUnless { it.isNull } == null
        if (used && request.targetAgentName.isNullOrBlank()) {
            parsed.forEach { (preset, base) -> base.agentName?.let { checkPresetTarget(preset, it, agents.targets) } }
        }
        return when {
            used -> {
                presetSelector.choose(
                    namespaceId = namespaceId,
                    userId = userId,
                    description = request.description,
                    presets = presets,
                    agentSystemPrompt =
                        loopSystemPrompt(
                            namespaceId = namespaceId,
                            userId = userId,
                            agents = agents,
                            presets = presets,
                        ),
                )
            }

            else -> {
                PresetOutcome()
            }
        }
    }

    /**
     * The loop agent's system prompt (namespace context, lexical definitions included), for the preset choice to
     * understand the description as the loop agent does. Resolved only when the LLM is asked, i.e. several presets.
     */
    private suspend fun loopSystemPrompt(
        namespaceId: UUID,
        userId: UUID,
        agents: Agents,
        presets: List<FlowConfigurationPreset>,
    ): String? =
        when {
            presets.size < 2 -> {
                null
            }

            else -> {
                agentService
                    .resolveDefinition(
                        agentConfigId =
                            agents.loops
                                .single()
                                .metadata.id,
                        namespaceId = namespaceId,
                        userId = userId,
                    ).systemPrompt
            }
        }

    private fun presetLabel(preset: FlowConfigurationPreset) = "flowConfigurationPresets['${preset.name}'].baseFlowConfiguration"

    private fun checkPresetTarget(
        preset: FlowConfigurationPreset,
        agentName: String,
        targets: List<AgentConfig>,
    ) {
        if (targets.none { it.name.equals(agentName, ignoreCase = true) }) {
            throw BadRequestException(
                "Unknown target agent '$agentName' in ${presetLabel(preset)}.act.agentName. Available: ${targets.map { it.name }}",
            )
        }
    }

    // -------------------------------------------------------------------------
    // Inputs: the request, completed by its base flow configuration
    // -------------------------------------------------------------------------

    /** What the request provides, each explicit field winning over its counterpart in the base flow configuration. */
    private data class Inputs(
        val targetAgentName: String?,
        val prompt: String?,
        val searchToolName: String?,
        val baseParams: JsonNode?,
    )

    /** The parts of a base flow configuration. */
    private data class ParsedBase(
        val tool: String?,
        val params: JsonNode?,
        val agentName: String?,
        val prompt: String?,
    )

    /**
     * [base] is the request's base flow configuration, or the chosen preset's (already checked by [preset]). Every
     * part of it is read first, so that a malformed one is a 400 even when overridden by a request field.
     */
    private fun inputs(
        request: ScheduledPromptGenerationRequest,
        base: JsonNode?,
    ): Inputs {
        val parsed = parseBase(base, "baseFlowConfiguration")
        return Inputs(
            targetAgentName = request.targetAgentName?.takeIf { it.isNotBlank() } ?: parsed.agentName,
            prompt = request.prompt?.takeIf { it.isNotBlank() } ?: parsed.prompt,
            searchToolName = request.searchToolName?.takeIf { it.isNotBlank() } ?: parsed.tool,
            baseParams = parsed.params,
        )
    }

    /** Reads [base] (null or JSON null: nothing); a part of the wrong type is a 400 naming [label]. */
    private fun parseBase(
        base: JsonNode?,
        label: String,
    ): ParsedBase {
        val node = base?.takeUnless { it.isNull }
        val search = node?.get("search")
        val act = node?.get("act")
        return ParsedBase(
            tool = baseText(node = search, field = "tool", path = "$label.search.tool"),
            params = baseParams(search = search, path = "$label.search.params"),
            agentName = baseText(node = act, field = "agentName", path = "$label.act.agentName"),
            prompt = baseText(node = act, field = "prompt", path = "$label.act.prompt"),
        )
    }

    /** A string of a base flow configuration: null when absent or blank, a 400 when of another type. */
    private fun baseText(
        node: JsonNode?,
        field: String,
        path: String,
    ): String? {
        val value = node?.get(field)?.takeUnless { it.isNull }
        return when {
            value == null -> null
            value.isTextual -> value.asText().takeIf { it.isNotBlank() }
            else -> throw BadRequestException("$path must be a string")
        }
    }

    /** `search.params` of a base flow configuration: null when absent, a 400 when not a JSON object. */
    private fun baseParams(
        search: JsonNode?,
        path: String,
    ): JsonNode? {
        val params = search?.get("params")?.takeUnless { it.isNull }
        return when {
            params == null || params.isObject -> params
            else -> throw BadRequestException("$path must be a JSON object")
        }
    }

    // -------------------------------------------------------------------------
    // 1. Agents, and the candidates once the inputs are known
    // -------------------------------------------------------------------------

    /** The agents of the namespace, before the inputs are known: they do not depend on the preset. */
    private data class Agents(
        val loops: List<AgentConfig>,
        val targets: List<AgentConfig>,
        val providedLoop: AgentConfig?,
    )

    private data class Candidates(
        val loops: List<AgentConfig>,
        val targets: List<AgentConfig>,
        val providedLoop: AgentConfig?,
        val providedTarget: AgentConfig?,
    ) {
        /** The loop agent needs no choice when it is provided or when there is a single candidate. */
        val obviousLoop: AgentConfig? get() = providedLoop ?: loops.singleOrNull()
    }

    /**
     * The agents of the namespace, whatever is deployed to the caller: see step 1 of the class KDoc.
     *
     * Throws [UnprocessableEntityException] (422) when the namespace has no LOOP agent, several, or no target agent,
     * [BadRequestException] (400) when the request names a loop or target agent that is not a candidate.
     */
    private fun agents(
        namespaceId: UUID,
        request: ScheduledPromptGenerationRequest,
    ): Agents {
        val namespaceAgents = agentConfigService.findByNamespace(namespaceId, withDisabled = false)
        val platformAgents = agentConfigService.findByNamespace(null, withDisabled = false)
        val loops = namespaceAgents.filter { it.resolvedExecutionMode == ExecutionMode.LOOP && !it.isFilesystemOnly }
        val targets =
            withShadowing(namespaceAgents + platformAgents).filter { it.resolvedExecutionMode != ExecutionMode.LOOP }
        when {
            loops.isEmpty() -> throw UnprocessableEntityException("No LOOP agent in this namespace: a scheduled loop needs one.")

            loops.size > 1 -> throw UnprocessableEntityException(
                "This namespace has ${loops.size} LOOP agents ${loops.map { it.name }}: a scheduled loop needs exactly one.",
            )

            targets.isEmpty() -> throw UnprocessableEntityException("No agent in this namespace to start for each search result.")
        }
        // Checked here, before any LLM call; resolved again by candidates(), which also sees the base's target.
        request.targetAgentName?.takeIf { it.isNotBlank() }?.let { pick(targets, it, "target agent") }
        return Agents(
            loops = loops,
            targets = targets,
            providedLoop = request.loopAgentName?.let { pick(loops, it, "LOOP agent") },
        )
    }

    /** [agents] completed by the target agent of the [inputs]: a 400 when it is not a candidate. */
    private fun candidates(
        agents: Agents,
        inputs: Inputs,
    ) = Candidates(
        loops = agents.loops,
        targets = agents.targets,
        providedLoop = agents.providedLoop,
        providedTarget = inputs.targetAgentName?.let { pick(agents.targets, it, "target agent") },
    )

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

        fun text(key: String): String? =
            json
                ?.get(key)
                ?.takeIf { it.isTextual }
                ?.asText()
                ?.takeIf { it.isNotBlank() }

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
        inputs: Inputs,
        candidates: Candidates,
    ): PlanOutcome {
        val needed =
            candidates.obviousLoop == null ||
                candidates.providedTarget == null ||
                inputs.prompt == null ||
                request.recurrence == null ||
                request.planning == null
        return when {
            needed -> planOutcome(callPlanner(namespaceId, userId, request, inputs, candidates))
            else -> PlanOutcome(skipped = true)
        }
    }

    private fun planOutcome(llm: StructuredLlmResult): PlanOutcome =
        PlanOutcome(json = llm.json, rawText = llm.rawText, warnings = llm.warnings)

    private suspend fun callPlanner(
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
        inputs: Inputs,
        candidates: Candidates,
    ): StructuredLlmResult {
        val (model, provider) =
            modelResolver.resolve(namespaceId, userId)
                ?: throw UnprocessableEntityException("No AI model is configured for namespace $namespaceId.")
        return structuredLlmClient.call(
            model,
            provider,
            PLANNING_SYSTEM_PROMPT,
            plannerMessage(request, inputs, candidates),
        )
    }

    private fun plannerMessage(
        request: ScheduledPromptGenerationRequest,
        inputs: Inputs,
        candidates: Candidates,
    ): String {
        val fixed =
            listOfNotNull(
                candidates.obviousLoop?.let { "loopAgentName" to it.name },
                candidates.providedTarget?.let { "targetAgentName" to it.name },
                inputs.prompt?.let { "prompt" to it },
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
            inputs.baseParams?.takeIf { it.size() > 0 }?.let { params ->
                appendLine()
                appendLine("Base search parameters (they may say who each case is opened with):")
                appendLine(objectMapper.writeValueAsString(params))
            }
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
        private val all: List<FieldOutcome<*>>
            get() =
                listOf(
                    loopAgent,
                    targetAgent,
                    prompt,
                    name,
                    recurrence,
                    planning,
                )
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
        inputs: Inputs,
        candidates: Candidates,
        plan: PlanOutcome,
    ) = ResolvedFields(
        loopAgent =
            resolveAgent(
                GeneratedField.LOOP_AGENT,
                candidates.providedLoop,
                candidates.loops.singleOrNull(),
                plan,
                "loopAgentName",
                candidates.loops,
            ),
        targetAgent =
            resolveAgent(
                GeneratedField.TARGET_AGENT,
                candidates.providedTarget,
                null,
                plan,
                "targetAgentName",
                candidates.targets,
            ),
        prompt = resolvePrompt(inputs.prompt, plan),
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
            provided != null -> {
                FieldOutcome(provided, providedDecision(field, provided.name))
            }

            obvious != null -> {
                FieldOutcome(obvious, inferredDecision(field, obvious.name, ONLY_CANDIDATE_RATIONALE))
            }

            chosen != null -> {
                FieldOutcome(chosen, inferredDecision(field, chosen.name, plan.rationale(field)))
            }

            chosenName != null -> {
                undetermined<AgentConfig>(
                    "The LLM chose '$chosenName' as ${
                        label(
                            field,
                        )
                    }, which is not among the available agents ${candidates.map { it.name }}.",
                )
            }

            else -> {
                undetermined<AgentConfig>("The LLM did not choose a ${label(field)}.".takeUnless { plan.unusable })
            }
        }
    }

    private fun resolvePrompt(
        provided: String?,
        plan: PlanOutcome,
    ): FieldOutcome<String> {
        val proposed = plan.text("prompt")
        return when {
            !provided.isNullOrBlank() -> {
                FieldOutcome(provided, providedDecision(GeneratedField.PROMPT, null))
            }

            proposed != null -> {
                FieldOutcome(
                    proposed,
                    inferredDecision(GeneratedField.PROMPT, null, plan.rationale(GeneratedField.PROMPT)),
                )
            }

            else -> {
                undetermined<String>("The LLM did not propose a prompt for the started cases.".takeUnless { plan.unusable })
            }
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
                val derived =
                    description
                        .trim()
                        .lineSequence()
                        .first()
                        .take(MAX_GENERATED_NAME_LENGTH)
                FieldOutcome(derived, inferredDecision(GeneratedField.NAME, derived, "derived from the description"))
            }

            else -> {
                FieldOutcome(
                    proposed,
                    inferredDecision(GeneratedField.NAME, proposed, plan.rationale(GeneratedField.NAME)),
                )
            }
        }
    }

    private fun resolveRecurrence(
        provided: RecurrenceDto?,
        plan: PlanOutcome,
    ): FieldOutcome<RecurrenceDto> {
        val (proposed, problem) = parseValid(plan.json?.get("recurrence"), RecurrenceDto::class.java)
        return when {
            provided != null -> {
                FieldOutcome(provided, providedDecision(GeneratedField.RECURRENCE, null))
            }

            proposed != null -> {
                FieldOutcome(
                    proposed,
                    inferredDecision(GeneratedField.RECURRENCE, null, plan.rationale(GeneratedField.RECURRENCE)),
                )
            }

            else -> {
                undetermined<RecurrenceDto>(
                    problem
                        ?.let { "The recurrence proposed by the LLM $it." }
                        .takeUnless { plan.unusable },
                )
            }
        }
    }

    private fun resolvePlanning(
        provided: PlanningDto?,
        plan: PlanOutcome,
    ): FieldOutcome<PlanningDto> {
        val (proposed, problem) = parseValid(plan.json?.get("planning"), PlanningDto::class.java)
        return when {
            provided != null -> {
                FieldOutcome(provided, providedDecision(GeneratedField.PLANNING, null))
            }

            proposed != null -> {
                FieldOutcome(
                    proposed,
                    inferredDecision(GeneratedField.PLANNING, null, plan.rationale(GeneratedField.PLANNING)),
                )
            }

            else -> {
                FieldOutcome(
                    PlanningDto(startDate = LocalDate.now(clock), endType = SchedulerEndType.NEVER),
                    inferredDecision(GeneratedField.PLANNING, null, "default"),
                    listOfNotNull(
                        "The planning proposed by the LLM ${problem ?: "is missing"}.",
                        "Planning defaulted to: from today, never ends.",
                    ).joinToString(" "),
                )
            }
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
        val parsed =
            node?.takeUnless { it.isNull }?.let { present -> runCatching { objectMapper.treeToValue(present, type) } }
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
    ) = GeneratedFieldDecision(
        field = field,
        source = GeneratedFieldSource.INFERRED,
        value = value,
        rationale = rationale,
    )

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
        inputs: Inputs,
    ): SearchOutcome {
        val definition = agentService.resolveDefinition(fields.loopAgent.metadata.id, namespaceId, userId)
        val result =
            loopConfigGenerationService.generate(
                definition = definition,
                searchToolName = inputs.searchToolName,
                description = request.description,
                context =
                    "Target agent: ${fields.targetAgent.name} (agentConfigId=${fields.targetAgent.metadata.id}), " +
                        "the agent started for each search result.",
                baseParams = inputs.baseParams,
            )
        val block = result.loopConfig?.get("search")
        // A tool named in the request or in the base flow configuration is provided: it was not selected.
        val source =
            if (inputs.searchToolName != null) GeneratedFieldSource.PROVIDED else GeneratedFieldSource.INFERRED
        return SearchOutcome(
            block = block,
            decision =
                block?.let {
                    GeneratedFieldDecision(
                        field = GeneratedField.SEARCH,
                        source = source,
                        value = it.get("tool")?.asText(),
                    )
                },
            warning = result.warning,
            unusableRawText = result.rawResponse,
            toolDescription =
                block
                    ?.get("tool")
                    ?.asText()
                    ?.let { tool -> definition.tools.firstOrNull { it.name == tool }?.description },
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
        preset: PresetOutcome,
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
            decisions = listOfNotNull(preset.decision) + fields.decisions + listOfNotNull(search?.decision),
            warnings =
                preset.warnings + plan.warnings + fields.warnings + listOfNotNull(search?.warning) +
                    problems.map { it.message },
            chosenPreset = preset.preset?.name,
            rawResponse =
                when (valid) {
                    null -> search?.unusableRawText ?: plan.unusableRawText ?: preset.unusableRawText
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
                ?.let {
                    ValidationProblem(
                        "The generated flowConfiguration will not run as a loop payload: ${it.message}",
                        blocking = false,
                    )
                }
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

        private const val ONLY_CANDIDATE_RATIONALE = "only LOOP agent of the namespace"

        /** Keys of the planning answer's `rationale` object, per field. The preset is chosen by its own call, so it has none. */
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

            Each case is already a conversation between the target agent and the person the description wants to
            reach (the recipient: e.g. the owner of the assignment, the manager of the talent). The entity of the case
            is given to the target agent separately. The opening prompt is shown in that conversation: the target agent
            acts on it, and the recipient reads it.

            Produce ONLY a JSON object in this exact format:
            {
              "loopAgentName": "<one of the LOOP agent candidates>",
              "targetAgentName": "<one of the target agent candidates>",
              "prompt": "<opening message of each case, written as the recipient speaking to the target agent>",
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
            - The prompt is written in the language of the description, as the recipient speaking to the target agent:
              the recipient is "I" / "me", the target agent is "you". Never refer to the recipient in the third
              person (not "the owner", "the talent's manager", "the user you are talking with").
            - The prompt never asks to contact, notify, message, email or reach out to the recipient: the case already
              is the conversation with them. Turn such delivery verbs into what the conversation is about: what to
              analyze, suggest, review, or ask the recipient ("suggest them to me", "ask me to estimate...").
            - The prompt is about the single entity of the case: refer to it as "this <entity>" or "the provided
              <entity>". It must not mention ids, the search, or other entities.
            - When the "Base search parameters" clearly say who each case is opened with, as a single relation to the
              entity (e.g. its owner, its manager), the prompt may state it in the recipient's voice ("As the manager
              of this <entity>, I..."). Otherwise (no base search parameters, nothing about the recipient, or several
              relations), do not state how the recipient relates to the entity ("I own", "my <entity>"): the prompt
              must read right for any recipient.
            - The prompt must not mention the schedule or the recurrence ("every day", "each Monday"): they are
              configured separately.
            - Example. Description: "Every day, for each task due next week, contact its owner and ask them to confirm
              it will be done on time." Prompt: "This task is due next week. Help me review it, then ask me to confirm
              it will be done on time."
            - Example. Description: "Every Monday, for each team member with an incomplete profile, suggest
              improvements to their manager." Prompt: "Analyze the provided team member's profile. Identify what is
              missing and suggest concrete improvements to me."
            - Times are in UTC. "days" only applies to WEEK (empty list = same weekday as startDate).
            - endDate is required, and strictly after startDate, when endType is ON_DATE.
              maxOccurrenceCount is required, and positive, when endType is OCCURRENCES.
            - When the description says nothing about the start, start today; about the end, use NEVER.
            - Each rationale is one short sentence.
            - No explanation, no markdown, no code fences. Start your response with '{' and end it with '}'.
            """.trimIndent()
    }
}
