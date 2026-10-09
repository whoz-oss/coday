package io.whozoss.factory.capability

import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.proxy.AgentTurnExecutionResult
import org.springframework.stereotype.Component

/**
 * W8.3 [AgentTurnCapability] backed by the AgentOS HTTP proxy.
 *
 * It is a pure translation of the transport vocabulary
 * ([AgentTurnExecutionResult]) into the capability vocabulary
 * ([AgentTurnResult]); the durable `agent_step_attempts` lifecycle and the
 * evidence record are owned by [CapabilityExecutionService], exactly like the
 * `code` and `human` branches. The boundary stays HTTP: no AgentOS Kotlin type is
 * ever imported.
 *
 * A missing namespace or persona is a clean, explicit failure (never a silent
 * success), so the sequencer's failure rule blocks the dependents.
 */
@Component
class AgentOsAgentTurnCapability(
    private val client: AgentOsProxyClient,
) : AgentTurnCapability {

    override fun executeAgentTurn(request: AgentTurnRequest): AgentTurnResult {
        val namespaceId = request.namespaceId?.takeIf { it.isNotBlank() }
            ?: return AgentTurnResult.Failed(
                "AGENTOS_NAMESPACE_REQUIRED",
                "An agent turn requires the resolved namespaceId of the session.",
            )
        val persona = request.persona?.takeIf { it.isNotBlank() }
            ?: return AgentTurnResult.Failed(
                "AGENT_PERSONA_REQUIRED",
                "An agent step requires `responsibility.name` (the AgentOS persona).",
            )
        return when (
            val result = client.executeAgentTurn(
                namespaceId = namespaceId,
                persona = persona,
                stepId = request.stepId,
                workflowId = request.workflowId ?: request.stepId,
                brief = request.brief,
                externalUserId = request.externalUserId,
                attemptId = request.attemptId,
                capabilityToken = request.capabilityToken,
                caseId = request.caseId,
            )
        ) {
            is AgentTurnExecutionResult.Completed -> AgentTurnResult.Completed(
                status = "PASS",
                facts = result.facts,
            )
            is AgentTurnExecutionResult.Failed -> AgentTurnResult.Failed(
                code = result.code,
                message = result.message,
                facts = result.facts,
            )
        }
    }
}
