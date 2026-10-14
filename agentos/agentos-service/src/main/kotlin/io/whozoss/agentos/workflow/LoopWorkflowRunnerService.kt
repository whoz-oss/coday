package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.casePlugin.CaseLaunchRefusal
import io.whozoss.agentos.casePlugin.CreateCaseTool
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.context.UserSessionContextResolver
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextResult
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
 *    b. check the end-user can access the target agent — refuse otherwise. `CreateCase` checks it again; this
 *       one only spares the context provider call, which may be an external request, for users who would be refused;
 *    c. resolve the end-user's session context;
 *    d. call the `CreateCase` tool, **as the triggering user**, naming the end-user as the target and handing the
 *       session context over as its `context`. Fire-and-forget: the tool returns the new case id at once. The
 *       new cases are top-level cases whose origin is the loop's own case: stopping the loop only stops the
 *       iteration, the cases already started keep running on their own.
 *
 * ## Who is authorized
 *
 * The launches go through `CreateCase`, so its rules apply to the **triggering user**, not to the end-users: to
 * start a case for someone else they must administer the namespace. The loop adds no authorization of its own.
 *
 * Errors that prevent the whole run return [LoopRunOutcome.Aborted]; per-entity problems are
 * counted in [LoopRunOutcome.Completed] and never stop the loop.
 */
@Service
class LoopWorkflowRunnerService(
    private val objectMapper: ObjectMapper,
    private val userService: UserService,
    private val agentConfigService: AgentConfigService,
    private val limitsConfig: LimitsConfigProperties,
    private val userSessionContextResolver: UserSessionContextResolver,
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
                    add(act(item, payload.act, context, triggerUser, launchTool))
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
        val toolContext = toolContext(context, triggerUser)
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
     * The context every tool of the loop is called with: the triggering user, in the loop's namespace, from the
     * loop's own case, which `CreateCase` records as the origin of the cases it starts.
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
        caseId = context.caseId,
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
        if (!hasAgentAccess(endUserId, act.agentName, context.namespaceId)) {
            logger.warn { "[LoopWorkflowRunner] User $endUserId cannot access agent '${act.agentName}' — refusing" }
            return ItemOutcome.NoAgentAccess
        }

        // Resolve user session context (business context + preferred language).
        // PermanentFailure and TransientFailure are both treated as failures in v0 (no retry):
        // the entity is counted as failed and the loop continues with the next one.
        // Without a provider, resolve() returns Success(null) and execution continues without context.
        val preferredLanguage = endUser.preferredLanguage?.takeIf { it.isNotBlank() }
        val sessionContext: Map<String, Any?>? =
            when (val result = userSessionContextResolver.resolve(userExternalId, context.namespaceId)) {
                is UserContextResult.Success -> {
                    if (result.sessionContext == null && userSessionContextResolver.hasProvider()) {
                        logger.warn {
                            "[LoopWorkflowRunner] UserContextProvider returned Success(null)" +
                                " for entity='${item.entityId}' — no sessionContext will be injected"
                        }
                    }
                    userSessionContextResolver.mergePreferredLanguage(result.sessionContext, preferredLanguage)
                }

                is UserContextResult.PermanentFailure -> {
                    logger.error {
                        "[LoopWorkflowRunner] Permanent context failure for entity='${item.entityId}'" +
                            " user=$endUserId — counting as failed. Reason: ${result.reason}"
                    }
                    return ItemOutcome.Failed
                }

                is UserContextResult.TransientFailure -> {
                    logger.warn {
                        "[LoopWorkflowRunner] Transient context failure for entity='${item.entityId}'" +
                            " user=$endUserId — counting as failed (no retry in v0). Reason: ${result.reason}"
                    }
                    return ItemOutcome.Failed
                }
            }

        val input =
            buildMap<String, Any?> {
                put("agentName", act.agentName)
                put("prompt", act.prompt)
                // The triggering user is the default target: naming them would need the other-users
                // switch of the integration for nothing.
                if (endUserId != triggerUser.metadata.id) put("userId", endUserId.toString())
                put("context", addActiveContext(sessionContext, item))
            }

        return try {
            launchOutcome(launchTool.executeWithJson(objectMapper.writeValueAsString(input), toolContext(context, triggerUser)), item, context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "[LoopWorkflowRunner] '${CreateCaseTool.TOOL_NAME}' threw for entity '${item.entityId}'" }
            ItemOutcome.Failed
        }
    }

    /**
     * What a `CreateCase` result means for the loop. A refusal because the agent is not deployed to the end user
     * is the same case as the loop's own check; anything else is a failure. A refusal that comes from the
     * triggering user or from the integration (not an administrator, other users not allowed, agent outside the
     * allowlist) hits every entity alike: it shows in the log with its reason, and in the summary as failures.
     */
    private fun launchOutcome(
        result: ToolExecutionResult,
        item: SearchResultItem,
        context: LoopRunContext,
    ): ItemOutcome =
        when {
            result.success ->
                launchedCaseId(result)
                    ?.also {
                        logger.info {
                            "[LoopWorkflowRunner] Launched case $it for entity '${item.entityId}' " +
                                "(agent=${result.metadata["agentName"]}, loopCase=${context.caseId})"
                        }
                    }?.let { ItemOutcome.Launched(it) }
                    ?: ItemOutcome.Failed.also {
                        logger.error {
                            "[LoopWorkflowRunner] '${CreateCaseTool.TOOL_NAME}' reported success without a case id " +
                                "for entity '${item.entityId}': ${result.output}"
                        }
                    }

            result.errorType == CaseLaunchRefusal.AGENT_NOT_AVAILABLE.name ->
                ItemOutcome.NoAgentAccess.also {
                    logger.warn { "[LoopWorkflowRunner] Agent not available for entity '${item.entityId}': ${result.output}" }
                }

            else ->
                ItemOutcome.Failed.also {
                    logger.error {
                        "[LoopWorkflowRunner] '${CreateCaseTool.TOOL_NAME}' refused entity '${item.entityId}' " +
                            "(${result.errorType}): ${result.output}"
                    }
                }
        }

    /** The new case id, from the structured output of the tool, else from its metadata. */
    private fun launchedCaseId(result: ToolExecutionResult): UUID? =
        (result.structuredOutput?.get("caseId")?.asText() ?: result.metadata["caseId"]?.toString())
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    /**
     * [AgentConfigService.findDeployedByNamespaceIdAndUserIdAndName] is a prefix match, so the
     * result is narrowed to an exact (case-insensitive) name — same as [io.whozoss.agentos.agent.AgentServiceImpl.findAgentByName].
     */
    private fun hasAgentAccess(
        userId: UUID,
        agentName: String,
        namespaceId: UUID,
    ): Boolean =
        agentConfigService
            .findDeployedByNamespaceIdAndUserIdAndName(
                namespaceId = namespaceId,
                userId = userId,
                agentName = agentName.lowercase(),
            ).any { it.name.equals(agentName, ignoreCase = true) }

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

private fun addActiveContext(
    sessionContext: Map<String, Any?>?,
    item: SearchResultItem,
): Map<String, Any?> =
    buildMap {
        if (sessionContext != null) putAll(sessionContext)
        put(
            "activeContext",
            listOf(
                mapOf(
                    "type" to item.entityType,
                    "data" to mapOf("id" to item.entityId),
                ),
            ),
        )
    }
