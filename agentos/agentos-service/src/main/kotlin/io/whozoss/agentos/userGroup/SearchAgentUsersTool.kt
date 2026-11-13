package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import io.whozoss.agentos.workflow.SearchResult
import io.whozoss.agentos.workflow.SearchResultItem
import io.whozoss.agentos.workflow.SearchResultMetadata
import io.whozoss.agentos.workflow.SearchResultTarget
import java.util.UUID

/**
 * Search flavour of [ListAgentUsersTool]: same audience, same input, but the structured output is a typed
 * [SearchResult], so the tool can be the `searchToolName` of a loop-mode scheduled prompt (SEARCH step of an
 * [io.whozoss.agentos.agent.AgentLoop]). It declares [SearchResult.SCHEMA] as output schema.
 *
 * The retrieval, the permission checks (done by [usersByAgentLoader]) and the errors are shared with
 * [ListAgentUsersTool] through [retrieveAgentUsers].
 *
 * Each member becomes one hit. The hit's entity is the user itself, so both [SearchResultItem.entityId] and the
 * single `User` [SearchResultTarget] carry the user's **external id**: by contract the item's `entityId` is an
 * external identifier (the loop hands it to the started case as `activeContext`), and the target must be the
 * external id because the loop resolves the end user with
 * [io.whozoss.agentos.user.UserService.findByExternalId]. The internal user id is already available to the
 * started case's tools through [ToolContext].
 *
 * @param configName IntegrationConfig name used as tool-name prefix. Null for bare name.
 * @param usersByAgentLoader Lambda: (agentConfigId, namespaceId, userId) → members, or null. See [ListAgentUsersTool].
 */
class SearchAgentUsersTool(
    configName: String? = null,
    private val usersByAgentLoader: suspend (agentConfigId: UUID, namespaceId: UUID, userId: UUID?) -> List<UserGroupMember>?,
) : StandardTool<ListAgentUsersTool.Input> {
    override val name: String = configName?.let { "${it}__searchAgentUsers" } ?: "searchAgentUsers"

    override val description: String =
        """
        Search the users who have access to a given agent through UserGroup deployment, as search results
        (one hit per user, targeting that user).

        Same audience as listAgentUsers: members (MEMBER or ADMIN) of at least one UserGroup of the current
        namespace that has the agent deployed to it, each returned once. Each hit is the user, identified by
        their external id. In a conversation, prefer listAgentUsers, which returns the users' details (role,
        email, names); this tool is meant to be the search tool of an agent loop, which starts one case per hit.
        """.trimIndent()

    override val inputSchema: String = AGENT_USERS_INPUT_SCHEMA

    override val version: String = "1.0.0"
    override val paramType: Class<ListAgentUsersTool.Input> = ListAgentUsersTool.Input::class.java

    override fun outputSchema(): JsonNode = SearchResult.SCHEMA

    override suspend fun execute(
        input: ListAgentUsersTool.Input?,
        context: ToolContext,
    ): ToolExecutionResult =
        retrieveAgentUsers(name, input, context, usersByAgentLoader) { agentConfigId, members ->
            ToolExecutionResult.success(
                output = agentUsersSummary(agentConfigId, members),
                structuredOutput = mapper.valueToTree<JsonNode>(members.toSearchResult()),
            )
        }

    private fun List<UserGroupMember>.toSearchResult() =
        SearchResult(
            data =
                map { m ->
                    SearchResultItem(
                        entityType = USER,
                        entityId = m.externalId,
                        targets = listOf(SearchResultTarget(entityType = USER, entityId = m.externalId)),
                    )
                },
            metadata = SearchResultMetadata(totalCount = size.toLong(), next = null),
        )

    companion object {
        private const val USER = "User"
        private val mapper = jacksonObjectMapper()
    }
}
