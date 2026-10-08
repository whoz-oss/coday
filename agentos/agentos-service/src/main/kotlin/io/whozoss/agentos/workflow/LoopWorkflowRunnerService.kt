package io.whozoss.agentos.workflow

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agentConfig.AgentConfigService
import io.whozoss.agentos.config.LimitsConfigProperties
import io.whozoss.agentos.context.UserSessionContextResolver
import io.whozoss.agentos.sdk.scheduledPrompt.UserContextResult
import io.whozoss.agentos.sdk.tool.ToolContext
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
 *    Authorization (who can trigger a loop) is enforced at the trigger point, not here.
 * 2. SEARCH: call the Search tool with [AgentLoopSearch.params] (first page only) and
 *    parse its structured output as [SearchResult].
 * 3. ACT, for at most [LimitsConfigProperties.agentLoopMaxItems] entities:
 *    a. resolve the end-user via [UserService.findByExternalId] — skip if unknown;
 *    b. check the end-user can access the target agent — refuse otherwise;
 *    c. launch one standalone case through [CaseLauncherService], fire-and-forget.
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
    private val caseLauncherService: CaseLauncherService,
) {
    suspend fun run(
        payload: AgentLoopPayload,
        context: LoopRunContext,
        shouldContinue: () -> Boolean,
    ): LoopRunOutcome {
        val triggerUser =
            context.triggerUser
                ?: return LoopRunOutcome.Aborted("An AgentLoop must be launched by an identified user.")

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
                    add(act(item, payload.act, context))
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
        val toolContext =
            ToolContext(
                namespaceId = context.namespaceId,
                userId = triggerUser.metadata.id,
                userExternalId = triggerUser.externalId,
                caseEvents = context.caseEvents,
                agentName = context.agentName,
            )
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

    private fun act(
        item: SearchResultItem,
        act: AgentLoopAct,
        context: LoopRunContext,
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

        val enrichedSessionContext: Map<String, Any?> = addActiveContext(sessionContext, item)

        return try {
            val launchedCaseId =
                caseLauncherService.launchCase(
                    namespaceId = context.namespaceId,
                    agentName = act.agentName,
                    task = act.prompt,
                    onBehalfOfUserId = endUserId,
                    sessionContext = enrichedSessionContext,
                )
            logger.info {
                "[LoopWorkflowRunner] Launched case $launchedCaseId for entity '${item.entityId}' " +
                    "(user=$endUserId, agent=${act.agentName}, loopCase=${context.caseId})"
            }
            ItemOutcome.Launched(launchedCaseId)
        } catch (e: Exception) {
            logger.error(e) { "[LoopWorkflowRunner] Failed to launch case for entity '${item.entityId}'" }
            ItemOutcome.Failed
        }
    }

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
