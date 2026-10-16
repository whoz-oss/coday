package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.casePlugin.CaseLaunchRefusal
import io.whozoss.agentos.casePlugin.CreateCaseTool
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import io.whozoss.agentos.util.tak
import kotlinx.coroutines.CancellationException
import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Executes the hardcoded SEARCH → ACT workflow of an [io.whozoss.agentos.agent.AgentLoop] run.
 *
 * Kept separate from [io.whozoss.agentos.agent.AgentLoop] so the workflow logic does not depend on how it is triggered:
 * [io.whozoss.agentos.agent.AgentLoop] only parses the case message and turns the [LoopRunOutcome] into case events.
 *
 * ## Flow
 *
 * 1. Guard: the triggering user must be identified.
 * 2. Guard: the `CreateCase` tool must be among the agent's tools, i.e. a `CASE_LAUNCHER` integration is bound
 *    to the loop agent. Checked before searching, so a misconfigured loop fails at once instead of after a
 *    search whose results it could not use. Whether `allowOtherUsers` is on is not checked: a loop whose end
 *    users are all the triggering user does not need it. Without it, every other end user is refused by the
 *    tool and counted as failed.
 * 3. SEARCH: call the Search tool with [AgentLoopSearch.params] (first page only) and
 *    parse its structured output as [SearchResult].
 * 4. ACT, for at most [LimitsConfigProperties.agentLoopMaxItems] entities:
 *    a. resolve the end-user via [UserService.findByExternalId] — skip if unknown;
 *    b. call the `CreateCase` tool, **as the triggering user**, naming the end-user as the target and handing the
 *       entity over as its `context` (`activeContext`). `CreateCase` checks that the end-user has the agent
 *       deployed and resolves their own context (what the `UserContextProvider` returns, their preferred
 *       language); the loop does neither. Fire-and-forget: the tool returns the new case id at once. The new
 *       cases are top-level cases whose origin is the loop's own case: stopping the loop only stops the
 *       iteration, the cases already started keep running on their own.
 *
 * ## Who is authorized
 *
 * The launches go through `CreateCase`, so its rules apply: the integration must allow other users, and each
 * end-user must read the namespace and have the agent deployed. The loop adds no authorization of its own.
 * An end-user without the agent is refused by `CreateCase` and counted as `noAgentAccess`. An end-user whose
 * context cannot be resolved makes `CreateCase` fail, and is counted as failed.
 *
 * Errors that prevent the whole run return [LoopRunOutcome.Aborted]; per-entity problems are
 * counted in [LoopRunOutcome.Completed] and never stop the loop.
 */
@Service
class LoopWorkflowRunnerService(
    private val objectMapper: ObjectMapper,
    private val userService: UserService,
    private val limitsConfig: LimitsConfigProperties,
) {
    suspend fun run(
        payload: AgentLoopPayload,
        context: LoopRunContext,
        shouldContinue: () -> Boolean,
    ): LoopRunOutcome {
        val triggerUser =
            context.triggerUser
                ?: return LoopRunOutcome.Aborted("An AgentLoop must be launched by an identified user.")

        val launchTool =
            context.tools.find { CreateCaseTool.isCreateCaseTool(it.name) }
                ?: return LoopRunOutcome.Aborted(
                    "The '${CreateCaseTool.TOOL_NAME}' tool is not available to agent '${context.agentName}' " +
                        "(available: ${context.tools.map { it.name }}). " +
                        "Bind a CASE_LAUNCHER integration to the agent (with allowOtherUsers enabled to start " +
                        "cases for users other than the one who triggers the loop).",
                )

        val search =
            when (val searchOutcome = search(payload, context, triggerUser)) {
                is SearchOutcome.Failure -> return LoopRunOutcome.Aborted(searchOutcome.reason)
                is SearchOutcome.Success -> searchOutcome.result
            }

        val maxItems = limitsConfig.agentLoopMaxItems
        val items = search.data.tak(maxItems)

        val outcomes =
            buildList {
                for (item in items) {
                    if (!shouldContinue()) break
                    add(
                        act(
                            item = item,
                            act = payload.act,
                            context = context,
                            triggerUser = triggerUser,
                            launchTool = launchTool,
                        ),
                    )
                }
            }

        val launchedCaseIds = outcomes.filterIsInstance<ItemOutcome.Launched>().map { it.caseId }
        val countByFailureType = outcomes.filter { it !is ItemOutcome.Launched }.groupingBy { it }.eachCount()

        return LoopRunOutcome.Completed(
            searchTool = payload.search.tool,
            returned = search.data.size,
            totalCount = search.metadata?.totalCount,
            hasMorePages = search.metadata?.next != null,
            launchedCaseIds = launchedCaseIds,
            unknownUser = countByFailureType[ItemOutcome.UnknownUser] ?: 0,
            noAgentAccess = countByFailureType[ItemOutcome.NoAgentAccess] ?: 0,
            failed = countByFailureType[ItemOutcome.Failed] ?: 0,
            overLimit = (search.data.size - maxItems).coerceAtLeast(0),
            maxItems = maxItems,
            targetAgent = payload.act.agentName,
            interrupted = !shouldContinue(),
        )
    }

    /**
     * Executes the SEARCH phase: invokes the configured tool and parses its output as
     * [SearchResult].
     *
     * **Whoz-specific coupling (V0):** [SearchResult], [SearchResultItem], and the
     * structured-output contract assumed here are tightly coupled to the Whoz Search tool
     * response schema. This method should be generalised once a stable cross-tool contract
     * exists (tracked in TODO: extract search-result contract to SDK).
     */
    private suspend fun search(
        payload: AgentLoopPayload,
        context: LoopRunContext,
        triggerUser: User,
    ): SearchOutcome {
        val toolName = payload.search.tool
        val searchTool =
            context.tools.find { it.name == toolName }
                ?: return SearchOutcome.Failure(
                    "Search tool '$toolName' is not available to agent '${context.agentName}' " +
                        "(available: ${context.tools.map { it.name }}). Check the agent integrations.",
                )
        val toolContext = toolContext(context = context, triggerUser = triggerUser)
        val paramsJson = objectMapper.writeValueAsString(payload.search.params)
        logger.debug { "[LoopWorkflowRunner] Search params JSON: $paramsJson" }
        val result =
            try {
                searchTool.executeWithJson(paramsJson, toolContext)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "[LoopWorkflowRunner] Search tool '$toolName' threw (caseId=${context.caseId})" }
                return SearchOutcome.Failure("Search tool '$toolName' failed: ${e.message}")
            }
        if (!result.success) {
            return SearchOutcome.Failure("Search tool '$toolName' returned a failure: ${result.output}")
        }
        if (result.structuredOutput == null) {
            logger.warn { "[LoopWorkflowRunner] Search tool '$toolName' returned no structured output. Raw output: ${result.output}" }
            return SearchOutcome.Failure(
                "Search tool '$toolName' returned no structured output. Check the tool's outputSchema(). Raw output: ${result.output}",
            )
        }
        val structured = result.structuredOutput
        return try {
            SearchOutcome.Success(objectMapper.treeToValue(structured, SearchResult::class.java))
        } catch (e: Exception) {
            logger.error(e) { "[LoopWorkflowRunner] Unparseable SearchResult from '$toolName' (caseId=${context.caseId})" }
            SearchOutcome.Failure("Search tool '$toolName' output does not match the expected format: ${e.message}")
        }
    }

    /**
     * The context every tool of the loop is called with: the triggering user, in the loop's namespace, with the
     * loop's own case events, from which `CreateCase` reads the case to record as the origin of the cases it starts.
     */
    private fun toolContext(
        context: LoopRunContext,
        triggerUser: User,
    ) = ToolContext(
        namespaceId = context.namespaceId,
        userId = triggerUser.metadata.id,
        userExternalId = triggerUser.externalId,
        caseEvents = context.caseEvents,
        agentName = context.agentName,
    )

    private suspend fun act(
        item: SearchResultItem,
        act: AgentLoopAct,
        context: LoopRunContext,
        triggerUser: User,
        launchTool: StandardTool<*>,
    ): ItemOutcome {
        val userTarget = item.targets?.firstOrNull { it.entityType.equals("User", ignoreCase = true) }
        if (userTarget == null) {
            logger.warn {
                "[LoopWorkflowRunner] No target with entityType='User' found for entity='${item.entityId}' " +
                    "(available targets: ${item.targets?.map { "${it.entityType}:${it.entityId}" }}) — skipping"
            }
            return ItemOutcome.UnknownUser
        }
        val userExternalId = userTarget.entityId
        val endUser = userService.findByExternalId(userExternalId)
        if (endUser == null) {
            logger.warn { "[LoopWorkflowRunner] No user found for userExternalId='$userExternalId' (entity='${item.entityId}') — skipping" }
            return ItemOutcome.UnknownUser
        }
        val endUserId = endUser.metadata.id

        // Only the entity travels as the context of the new case: the end-user's own context (provider,
        // preferred language) is resolved by CreateCase, which fails the launch if it cannot be.
        val input =
            buildMap<String, Any?> {
                put("agentName", act.agentName)
                put("prompt", act.prompt)
                put("userId", endUserId.toString())
                put("context", activeContextOf(item))
            }

        return try {
            val result =
                launchTool.executeWithJson(
                    objectMapper.writeValueAsString(input),
                    toolContext(context = context, triggerUser = triggerUser),
                )
            launchOutcome(result = result, item = item, context = context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[LoopWorkflowRunner] '${CreateCaseTool.TOOL_NAME}' threw for entity '${item.entityId}'" }
            ItemOutcome.Failed
        }
    }

    /**
     * What a `CreateCase` result means for the loop. A refusal because the agent is not deployed to the end user
     * counts as no agent access; anything else is a failure. A refusal that comes from the
     * integration (other users not allowed, agent outside the allowlist) hits every entity alike: it shows in
     * the log with its reason, and in the summary as failures.
     */
    private fun launchOutcome(
        result: ToolExecutionResult,
        item: SearchResultItem,
        context: LoopRunContext,
    ): ItemOutcome {
        if (!result.success) {
            if (result.errorType == CaseLaunchRefusal.AGENT_NOT_AVAILABLE.name) {
                logger.warn { "[LoopWorkflowRunner] Agent not available for entity '${item.entityId}': ${result.output}" }
                return ItemOutcome.NoAgentAccess
            }
            logger.error {
                "[LoopWorkflowRunner] '${CreateCaseTool.TOOL_NAME}' refused entity '${item.entityId}' " +
                    "(${result.errorType}): ${result.output}"
            }
            return ItemOutcome.Failed
        }
        val caseId = launchedCaseId(result)
        if (caseId == null) {
            logger.error {
                "[LoopWorkflowRunner] '${CreateCaseTool.TOOL_NAME}' reported success without a case id " +
                    "for entity '${item.entityId}': ${result.output}"
            }
            return ItemOutcome.Failed
        }
        logger.info {
            "[LoopWorkflowRunner] Launched case $caseId for entity '${item.entityId}' " +
                "(agent=${result.metadata["agentName"]}, loopCase=${context.caseId})"
        }
        return ItemOutcome.Launched(caseId)
    }

    /** The new case id, from the structured output of the tool, as its output schema describes it. */
    private fun launchedCaseId(result: ToolExecutionResult): UUID? =
        result.structuredOutput
            ?.get("caseId")
            ?.asText()
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private sealed interface ItemOutcome {
        data class Launched(
            val caseId: UUID,
        ) : ItemOutcome

        data object UnknownUser : ItemOutcome

        data object NoAgentAccess : ItemOutcome

        data object Failed : ItemOutcome
    }

    private sealed interface SearchOutcome {
        data class Success(
            val result: SearchResult,
        ) : SearchOutcome

        data class Failure(
            val reason: String,
        ) : SearchOutcome
    }

    companion object : KLogging()
}

/** The context the loop hands to `CreateCase`: the entity of the search hit, as the UI would send it. */
private fun activeContextOf(item: SearchResultItem): Map<String, Any?> =
    mapOf(
        "activeContext" to
            listOf(
                mapOf(
                    "type" to item.entityType,
                    "data" to mapOf("id" to item.entityId),
                ),
            ),
    )
