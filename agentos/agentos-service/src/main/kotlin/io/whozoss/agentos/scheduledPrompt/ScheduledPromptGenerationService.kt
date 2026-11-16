package io.whozoss.agentos.scheduledPrompt

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.agent.AgentService
import io.whozoss.agentos.agent.ModelResolver
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.generation.StructuredLlmClient
import io.whozoss.agentos.sdk.api.agentConfig.ExecutionMode
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
import jakarta.validation.Validator
import mu.KLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Builds a **proposal** of a scheduled prompt driving a LOOP agent from a natural-language description. Nothing is
 * persisted: the proposal is meant to be reviewed by a human, then POSTed to `POST /api/scheduled-prompts`.
 *
 * Every field of [ScheduledPromptGenerationRequest] but the description is optional. A provided field always wins;
 * a missing one is decided by an LLM.
 *
 * ## Pipeline (at most two LLM calls)
 *
 * 1. **Candidates, no LLM.** The agents deployed to the caller, split into LOOP agents (persisted ones only: a
 *    filesystem agent cannot back a scheduled prompt) and target agents (every non-LOOP agent). A provided name that
 *    is not a candidate is a 400. No LOOP candidate is a 422. A single LOOP candidate is picked without the LLM.
 * 2. **Planning call.** With the namespace default model ([ModelResolver]), the LLM picks what is missing among the
 *    loop agent, the target agent, the prompt, the name, the recurrence and the planning. It only sees the candidate
 *    names, and its choices are checked against them. Skipped when all of these are provided; the name is then
 *    derived from the description.
 * 3. **Search call.** [LoopConfigGenerationService] generates the search block with the loop agent's own tools and
 *    model, given the target agent's name and id as context (a search such as `searchAgentUsers` needs that id).
 * 4. **Assembly.** `flowConfiguration = { search, act: { agentName } }`, without `act.prompt`, which the scheduler sets
 *    from `promptContent` at run time. The result is checked to parse as an [AgentLoopPayload] once that prompt is set.
 *
 * A part that cannot be determined (an unusable LLM answer, an invalid recurrence, no search block) yields a null
 * proposal and warnings saying why. An invalid planning from the LLM falls back to "from today, never ends", with a
 * warning. LLM exceptions are not caught.
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
    private val objectMapper: ObjectMapper,
    private val validator: Validator,
    private val clock: Clock,
) {
    suspend fun generate(
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
    ): ScheduledPromptGenerationResult {
        // 1. Candidates
        val accessible = withShadowing(agentConfigService.findDeployedByNamespaceIdAndUserIdAndName(namespaceId, userId, null))
        val loopCandidates = accessible.filter { it.resolvedExecutionMode == ExecutionMode.LOOP && !it.isFilesystemOnly }
        val targetCandidates = accessible.filter { it.resolvedExecutionMode != ExecutionMode.LOOP }
        if (loopCandidates.isEmpty()) {
            throw UnprocessableEntityException("No LOOP agent available to you in this namespace: a scheduled loop needs one.")
        }
        if (targetCandidates.isEmpty()) {
            throw UnprocessableEntityException("No agent available to you in this namespace to start for each search result.")
        }
        val providedLoop = request.loopAgentName?.let { pick(loopCandidates, it, "LOOP agent") }
        val providedTarget = request.targetAgentName?.let { pick(targetCandidates, it, "target agent") }
        val onlyLoop = loopCandidates.singleOrNull().takeIf { providedLoop == null }

        val decisions = mutableListOf<GeneratedFieldDecision>()
        val warnings = mutableListOf<String>()
        fun fail(rawResponse: String? = null) = ScheduledPromptGenerationResult(null, decisions.toList(), warnings.toList(), rawResponse)

        // 2. Planning call
        val prompt = request.prompt?.takeIf { it.isNotBlank() }
        val loopKnown = providedLoop ?: onlyLoop
        val needsPlanning =
            loopKnown == null || providedTarget == null || prompt == null || request.recurrence == null || request.planning == null
        val plan =
            if (needsPlanning) {
                val llm = callPlanner(namespaceId, userId, request, loopCandidates, targetCandidates, loopKnown, providedTarget, prompt)
                if (llm.json == null) {
                    warnings += if (llm.returnedNothing) "The LLM returned no content." else "The LLM response could not be parsed as a JSON object."
                    return fail(llm.rawText)
                }
                PlanAnswer(llm.json, llm.rawText, llm.usedCodeFence)
            } else {
                null
            }
        if (plan?.usedFence == true) warnings += "JSON was extracted from a markdown code fence in the LLM response."

        val loopAgent =
            resolveAgent("loopAgent", providedLoop, onlyLoop, "only LOOP agent available", plan, "loopAgentName", loopCandidates, decisions, warnings)
                ?: return fail(plan?.raw)
        val targetAgent =
            resolveAgent("targetAgent", providedTarget, null, null, plan, "targetAgentName", targetCandidates, decisions, warnings)
                ?: return fail(plan?.raw)

        val finalPrompt =
            prompt?.also { decisions += providedDecision("prompt", null) }
                ?: plan?.text("prompt")?.also { decisions += inferredDecision("prompt", null, plan?.rationale("prompt")) }
                ?: run {
                    warnings += "The LLM did not propose a prompt for the started cases."
                    return fail(plan?.raw)
                }
        val name =
            plan?.text("name")?.also { decisions += inferredDecision("name", it, plan?.rationale("name")) }
                ?: request.description.trim().lineSequence().first().take(MAX_DERIVED_NAME_LENGTH).also {
                    decisions += inferredDecision("name", it, "derived from the description")
                }

        val recurrence =
            request.recurrence?.also { decisions += providedDecision("recurrence", null) }
                ?: parseValid<RecurrenceDto>(plan?.json?.get("recurrence"), "recurrence", warnings)
                    ?.also { decisions += inferredDecision("recurrence", null, plan?.rationale("recurrence")) }
                ?: return fail(plan?.raw)
        val planning =
            request.planning?.also { decisions += providedDecision("planning", null) }
                ?: parseValid<PlanningDto>(plan?.json?.get("planning"), "planning", warnings)
                    ?.also { decisions += inferredDecision("planning", null, plan?.rationale("planning")) }
                ?: PlanningDto(startDate = LocalDate.now(clock), endType = SchedulerEndType.NEVER).also {
                    warnings += "Planning defaulted to: from today, never ends."
                    decisions += inferredDecision("planning", null, "default")
                }

        // 3. Search call
        val definition = agentService.resolveDefinition(loopAgent.metadata.id, namespaceId, userId)
        val search =
            loopConfigGenerationService.generate(
                definition = definition,
                searchToolName = request.searchToolName,
                description = request.description,
                context = "Target agent: ${targetAgent.name} (agentConfigId=${targetAgent.metadata.id}), " +
                    "the agent started for each search result.",
            )
        search.warning?.let { warnings += it }
        val searchBlock = search.loopConfig?.get("search") ?: return fail(search.rawResponse)
        decisions +=
            GeneratedFieldDecision(
                field = "search",
                source = if (request.searchToolName != null) GeneratedFieldSource.PROVIDED else GeneratedFieldSource.INFERRED,
                value = searchBlock.get("tool")?.asText(),
            )

        // 4. Assembly
        val flowConfiguration =
            objectMapper.createObjectNode().apply {
                set<JsonNode>("search", searchBlock)
                putObject("act").put("agentName", targetAgent.name)
            }
        checkRunnable(flowConfiguration, finalPrompt)?.let { warnings += it }

        logger.info {
            "[ScheduledPromptGeneration] Proposal for namespace=$namespaceId: loop='${loopAgent.name}', " +
                "target='${targetAgent.name}', tool=${searchBlock.get("tool")?.asText()}"
        }
        return ScheduledPromptGenerationResult(
            scheduledPrompt =
                ScheduledPromptDto(
                    namespaceId = namespaceId,
                    agentConfigId = loopAgent.metadata.id,
                    promptContent = finalPrompt,
                    name = name,
                    description = request.description,
                    recurrence = recurrence,
                    planning = planning,
                    enabled = true,
                    flowConfiguration = flowConfiguration,
                ),
            decisions = decisions.toList(),
            warnings = warnings.toList(),
        )
    }

    // -------------------------------------------------------------------------
    // Candidates
    // -------------------------------------------------------------------------

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

    /**
     * The agent for [field]: the provided one, else the obvious one (a single candidate), else the one the LLM chose
     * under [planKey], which must be a candidate. Null, with a warning, when there is none.
     */
    private fun resolveAgent(
        field: String,
        given: AgentConfig?,
        obvious: AgentConfig?,
        obviousRationale: String?,
        plan: PlanAnswer?,
        planKey: String,
        candidates: List<AgentConfig>,
        decisions: MutableList<GeneratedFieldDecision>,
        warnings: MutableList<String>,
    ): AgentConfig? {
        given?.let {
            decisions += providedDecision(field, it.name)
            return it
        }
        obvious?.let {
            decisions += inferredDecision(field, it.name, obviousRationale)
            return it
        }
        val chosen = plan?.text(planKey)
        val agent = chosen?.let { name -> candidates.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        if (agent == null) {
            warnings +=
                if (chosen == null) {
                    "The LLM did not choose a $field."
                } else {
                    "The LLM chose '$chosen' as $field, which is not among the available agents ${candidates.map { it.name }}."
                }
            return null
        }
        decisions += inferredDecision(field, agent.name, plan?.rationale(field))
        return agent
    }

    // -------------------------------------------------------------------------
    // Planning call
    // -------------------------------------------------------------------------

    private fun callPlanner(
        namespaceId: UUID,
        userId: UUID,
        request: ScheduledPromptGenerationRequest,
        loopCandidates: List<AgentConfig>,
        targetCandidates: List<AgentConfig>,
        loopKnown: AgentConfig?,
        targetKnown: AgentConfig?,
        prompt: String?,
    ): io.whozoss.agentos.generation.StructuredLlmResult {
        val (model, provider) =
            modelResolver.resolve(namespaceId, userId)
                ?: throw UnprocessableEntityException("No AI model is configured for namespace $namespaceId.")
        val fixed =
            listOfNotNull(
                loopKnown?.let { "loopAgentName" to it.name },
                targetKnown?.let { "targetAgentName" to it.name },
                prompt?.let { "prompt" to it },
                request.recurrence?.let { "recurrence" to objectMapper.writeValueAsString(it) },
                request.planning?.let { "planning" to objectMapper.writeValueAsString(it) },
            )
        val userMessage =
            buildString {
                appendLine("Today (UTC): ${LocalDate.now(clock)}")
                appendLine()
                appendLine("LOOP agent candidates (the agent running the automation):")
                loopCandidates.forEach { appendLine("- ${it.name}: ${it.description.orEmpty()}") }
                appendLine()
                appendLine("Target agent candidates (the agent started for each search result):")
                targetCandidates.forEach { appendLine("- ${it.name}: ${it.description.orEmpty()}") }
                if (fixed.isNotEmpty()) {
                    appendLine()
                    appendLine("Already decided (copy these values as is):")
                    fixed.forEach { (key, value) -> appendLine("- $key: $value") }
                }
                appendLine()
                appendLine("User description:")
                append(request.description)
            }
        return structuredLlmClient.call(model, provider, PLANNING_SYSTEM_PROMPT, userMessage)
    }

    /** The planning answer, with typed accessors. */
    private class PlanAnswer(
        val json: JsonNode,
        val raw: String?,
        val usedFence: Boolean,
    ) {
        fun text(key: String): String? = json.get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

        fun rationale(field: String): String? =
            json.get("rationale")?.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    /** Parses [node] as a [T] and runs Bean Validation on it; null, with a warning, when it is absent or invalid. */
    private inline fun <reified T : Any> parseValid(
        node: JsonNode?,
        field: String,
        warnings: MutableList<String>,
    ): T? {
        if (node == null || node.isNull) {
            warnings += "The LLM did not propose a $field."
            return null
        }
        val value =
            runCatching { objectMapper.treeToValue(node, T::class.java) }.getOrElse {
                warnings += "The $field proposed by the LLM could not be read: ${it.message}"
                return null
            }
        val violations = validator.validate(value)
        if (violations.isNotEmpty()) {
            warnings += "The $field proposed by the LLM is invalid: ${violations.joinToString("; ") { it.message }}"
            return null
        }
        return value
    }

    /**
     * Applies what the scheduler does at run time (`act.prompt` = the prompt content) and checks that the result parses
     * as an [AgentLoopPayload]. Returns a warning when it does not.
     */
    private fun checkRunnable(
        flowConfiguration: ObjectNode,
        prompt: String,
    ): String? {
        val atRunTime = flowConfiguration.deepCopy().also { (it.get("act") as ObjectNode).put("prompt", prompt) }
        return runCatching { objectMapper.treeToValue(atRunTime, AgentLoopPayload::class.java) }
            .exceptionOrNull()
            ?.let { "The generated flowConfiguration will not run as a loop payload: ${it.message}" }
    }

    private fun providedDecision(
        field: String,
        value: String?,
    ) = GeneratedFieldDecision(field = field, source = GeneratedFieldSource.PROVIDED, value = value)

    private fun inferredDecision(
        field: String,
        value: String?,
        rationale: String?,
    ) = GeneratedFieldDecision(field = field, source = GeneratedFieldSource.INFERRED, value = value, rationale = rationale)

    companion object : KLogging() {
        private const val MAX_DERIVED_NAME_LENGTH = 80

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
              "name": "<short name of the automation, max 80 characters>",
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
