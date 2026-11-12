package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.util.UUID

/**
 * Search flavour of [ListAgentUsersTool]: same audience, same authorization, same input, but the structured
 * output follows the search contract of an [io.whozoss.agentos.agent.AgentLoop] SEARCH step
 * ([io.whozoss.agentos.workflow.SearchResult]), so it can be used as the `searchToolName` of a loop-mode
 * scheduled prompt.
 *
 * It wraps a [ListAgentUsersTool] and reshapes its structured output, so the retrieval, the permission checks
 * and the error handling exist only there. Each member becomes one hit: `entityId` is the internal user id and
 * its single `User` target carries the external id, which is what the loop resolves the end user from.
 *
 * @param configName IntegrationConfig name used as tool-name prefix. Null for bare name.
 * @param usersByAgentLoader Lambda: (agentConfigId, namespaceId, userId) → members, or null. See [ListAgentUsersTool].
 */
class SearchAgentUsersTool(
    private val configName: String? = null,
    usersByAgentLoader: suspend (agentConfigId: UUID, namespaceId: UUID, userId: UUID?) -> List<UserGroupMember>?,
) : StandardTool<ListAgentUsersTool.Input> {
    private val listTool = ListAgentUsersTool(configName = configName, usersByAgentLoader = usersByAgentLoader)

    override val name: String = configName?.let { "${it}__searchAgentUsers" } ?: "searchAgentUsers"

    override val description: String =
        """
        Search the users who have access to a given agent through UserGroup deployment, as search results
        (one hit per user, targeting that user).

        Same audience as listAgentUsers: members (MEMBER or ADMIN) of at least one UserGroup of the current
        namespace that has the agent deployed to it, each returned once. Meant to be used as the search tool
        of an agent loop, which starts one case per hit.
        """.trimIndent()

    override val inputSchema: String = listTool.inputSchema

    override val version: String = "1.0.0"
    override val paramType: Class<ListAgentUsersTool.Input> = ListAgentUsersTool.Input::class.java

    override fun outputSchema(): JsonNode = SEARCH_RESULT_SCHEMA

    override suspend fun execute(
        input: ListAgentUsersTool.Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val result = listTool.execute(input, context)
        if (!result.success) return result
        return result.copy(structuredOutput = toSearchResult(result.structuredOutput))
    }

    /** Reshapes the member array of [ListAgentUsersTool] into `{ data: [...], metadata: {...} }`. */
    private fun toSearchResult(members: JsonNode?): JsonNode {
        val data: ArrayNode = mapper.createArrayNode()
        members?.forEach { member ->
            data.addObject().apply {
                put("entityType", USER)
                put("entityId", member["userId"].asText())
                putArray("targets").addObject().apply {
                    put("entityType", USER)
                    put("entityId", member["externalId"].asText())
                }
            }
        }
        return mapper.createObjectNode().apply {
            set<JsonNode>("data", data)
            putObject("metadata").apply {
                put("totalCount", data.size())
                putNull("next")
            }
        }
    }

    companion object {
        private const val USER = "User"
        private val mapper = jacksonObjectMapper()

        val SEARCH_RESULT_SCHEMA: JsonNode =
            mapper.readTree(
                """
                {
                  "type": "object",
                  "properties": {
                    "data": {
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "entityType": { "type": "string" },
                          "entityId":   { "type": "string" },
                          "targets": {
                            "type": "array",
                            "items": {
                              "type": "object",
                              "properties": {
                                "entityType": { "type": "string" },
                                "entityId":   { "type": "string" }
                              },
                              "required": ["entityType", "entityId"]
                            }
                          }
                        },
                        "required": ["entityType", "entityId"]
                      }
                    },
                    "metadata": {
                      "type": "object",
                      "properties": {
                        "totalCount": { "type": "integer" },
                        "next":       { "type": ["string", "null"] }
                      }
                    }
                  },
                  "required": ["data", "metadata"]
                }
                """.trimIndent(),
            )
    }
}
