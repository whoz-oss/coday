package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.agentos.agent.AgentInterrupt
import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * `AGENTOS_CONFIG__propose`: freezes a configuration change and asks the user to apply it.
 *
 * Writes nothing. A valid plan suspends the run on a question addressed to the user the agent runs
 * for; the plan itself rides on this call's `ToolResponseEvent` (`structuredOutput`), and the card
 * applies it through `ConfigChangeController` when that user clicks Apply. The answer the server
 * posts then resumes the agent.
 */
class ConfigProposeTool(
    private val callerId: UUID?,
    private val namespaceId: UUID,
    private val caseId: UUID?,
    private val agentName: String,
    private val configPlanner: ConfigPlanner,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : StandardTool<ProposeInput> {
    override val name: String = ConfigAgentIntegration.PROPOSE_TOOL_NAME
    override val version: String = "1.0.0"
    override val paramType: Class<ProposeInput> = ProposeInput::class.java

    override val description: String =
        """
        Propose a change to the AgentOS configuration. Nothing is written: the user reviews the exact plan
        in a card and clicks Apply or Reject; you then receive the result as their answer.
        Read the current state first with ${ConfigAgentIntegration.READ_TOOL_NAME}: UPDATE, ENABLE, DISABLE, DEPLOY
        and UNDEPLOY target an existing 'id'; CREATE carries a 'scope' and a 'value' with the fields of the new entity
        (the same fields as the AgentOS REST API: e.g. an agent has name, description, instructions, modelName,
        integrations {"INTEGRATION_NAME": null or ["tool"]}, subAgents; a prompt has name, content [..], agentConfigId;
        an integration has name, integrationType, description, parameters, authSettingName; an API key auth setting
        has name, authType "API_KEY", apiKey; a skill has name, description, body; a namespace has name, description).
        To use the id of an entity created earlier in the same plan, write {"${'$'}op": "<ref>"}.
        Never write a secret (API key, token, password): declare its path in 'secrets' and the user types it in the card.
        A new agent needs 'deployTo': NAMESPACE so members can use it, or NONE.
        Entities live on the platform, in this namespace, or in a namespace the same plan creates (CREATE NAMESPACE, then
        scope.namespaceId {"${'$'}op": "<its ref>"} on the entities created in it and on the DEPLOY of existing platform agents).
        No other namespace can be read or changed from here.
        """.trimIndent()

    override val inputSchema: String =
        """
        {
          "type": "object",
          "properties": {
            "summary": { "type": "string", "description": "One sentence the user reads first." },
            "operations": {
              "type": "array",
              "minItems": 1,
              "maxItems": ${ConfigAgentLimits.MAX_OPERATIONS},
              "items": {
                "type": "object",
                "properties": {
                  "ref": { "type": "string", "description": "Short unique name of this operation, used by {\"${'$'}op\": ref}." },
                  "kind": { "type": "string", "enum": ["AGENT", "SKILL", "PROMPT", "INTEGRATION", "AUTH_SETTING", "NAMESPACE"] },
                  "action": { "type": "string", "enum": ["CREATE", "UPDATE", "ENABLE", "DISABLE", "DEPLOY", "UNDEPLOY"] },
                  "id": { "type": "string", "description": "Target id, for every action but CREATE." },
                  "scope": {
                    "type": "object",
                    "description": "CREATE (but not of a NAMESPACE), and DEPLOY or UNDEPLOY on a namespace the plan creates. namespaceId: \"current\" for this namespace, {\"${'$'}op\": \"<ref>\"} for a namespace created by this plan, null for platform. userId: \"me\" for a personal entity, null for a shared one.",
                    "properties": {
                      "namespaceId": { "type": ["string", "object", "null"] },
                      "userId": { "type": ["string", "null"] }
                    }
                  },
                  "value": { "type": "object", "description": "CREATE only: the fields of the new entity, without id, namespaceId or userId." },
                  "changes": {
                    "type": "array",
                    "description": "UPDATE only: fields to set or remove, by JSON Pointer path (e.g. /instructions, /integrations/MY_API).",
                    "items": {
                      "type": "object",
                      "properties": {
                        "path": { "type": "string" },
                        "value": {},
                        "remove": { "type": "boolean" }
                      },
                      "required": ["path"]
                    }
                  },
                  "secrets": {
                    "type": "array",
                    "description": "Paths the user fills in the card, e.g. /apiKey.",
                    "items": {
                      "type": "object",
                      "properties": { "path": { "type": "string" }, "label": { "type": "string" } },
                      "required": ["path"]
                    }
                  },
                  "deployTo": { "type": "string", "enum": ["NAMESPACE", "NONE"], "description": "CREATE of an AGENT only." }
                },
                "required": ["kind", "action"]
              }
            }
          },
          "required": ["operations"]
        }
        """.trimIndent()

    override suspend fun execute(
        input: ProposeInput?,
        context: ToolContext,
    ): ToolExecutionResult =
        when {
            callerId == null -> ToolExecutionResult.error("Proposing a change needs a user: this run has none.", errorType = "NO_USER")
            // The definition preview resolves the tools without a case: there is nobody to ask there.
            caseId == null -> ToolExecutionResult.error("Proposing a change needs a conversation (case) to ask the user in.", errorType = "NO_CASE")
            else -> propose(input ?: ProposeInput(), callerId, caseId)
        }

    /** Returns the planner's errors, or suspends the run on the question when the plan is valid. */
    private fun propose(
        input: ProposeInput,
        userId: UUID,
        case: UUID,
    ): ToolExecutionResult {
        val proposal =
            ProposalContext(
                callerId = userId,
                namespaceId = namespaceId,
                caseId = case,
                agentName = agentName,
                questionId = UUID.randomUUID(),
                now = Instant.now(clock),
            )
        return when (val result = configPlanner.plan(input, proposal)) {
            is PlanningResult.Invalid ->
                ToolExecutionResult.error(
                    "The proposal is invalid, nothing was submitted to the user. Fix these points and call again:\n" +
                        result.errors.joinToString("\n") { "- $it" },
                    errorType = "INVALID_PROPOSAL",
                )
            is PlanningResult.Planned ->
                throw AgentInterrupt.AwaitAnswer(
                    question = questionText(result.plan),
                    userId = userId,
                    questionId = proposal.questionId,
                    structuredOutput = objectMapper.valueToTree(result.plan),
                )
        }
    }

    private fun questionText(plan: ConfigChangePlan): String =
        buildString {
            append(plan.summary)
            append("\n\n")
            plan.order.mapNotNull(plan::operation).forEach { append("- ").append(describe(it)).append('\n') }
            plan.warnings.forEach { append("\nWarning: ").append(it) }
            append("\n\nReview the plan in the AgentOS card, then apply or reject it.")
        }

    companion object {
        fun describe(op: PlannedOperation): String {
            val subject =
                op.target?.name
                    ?: op.value
                        ?.path("name")
                        ?.asText()
                        .orEmpty()
            return "${op.ref}: ${op.action} ${op.kind} $subject".trimEnd()
        }
    }
}
