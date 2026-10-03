package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.spi.ToolGrantDecision
import io.whozoss.agentos.sdk.spi.ToolGrantPolicy
import io.whozoss.agentos.sdk.tool.ToolContext
import org.pf4j.Extension

/**
 * Fail-closed tool-grant policy for the Factory result channel.
 *
 * `FACTORY__submit_step_result` authorises against a case-scoped Factory capability
 * (bound out-of-band by the Factory, never by the model). When no such capability is
 * active for the running case, the tool is denied so an agent can never be prompted into
 * a submission it is not entitled to make.
 *
 * `FACTORY__ask_step_question` (Phase 4 ask-step-question) authorises against the SAME
 * case-scoped capability: asking a question resolves the binding read-only, so a case
 * without an active capability cannot durably record a question either.
 *
 * Every other tool is left untouched ([ToolGrantDecision.Neutral]), matching the SPI's
 * pass-through contract. In particular the six Phase 6 read-only Workstream
 * Agent tools (`get_workstream`, `list_workflows`, `get_workflow`,
 * `get_step_attempts`, `get_blockers`, `get_required_human_actions`) are
 * intentionally NOT capability-gated: they are pure reads with no mutation
 * surface, so no case-scoped capability is required.
 */
@Extension
class FactoryToolGrantPolicy
    @JvmOverloads
    constructor(
        private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
    ) : ToolGrantPolicy {
        override fun evaluateToolGrant(
            agentName: String?,
            toolName: String,
            context: ToolContext,
        ): ToolGrantDecision {
            if (toolName !in CAPABILITY_BOUND_TOOLS) return ToolGrantDecision.Neutral
            return runCatching {
                val caseId = context.caseEvents.map { it.caseId }.distinct().singleOrNull()
                    ?: return@runCatching ToolGrantDecision.Deny(setOf(toolName), "no single controlling case")
                val bound = services().stepResultBindings.contextForCase(caseId, context.namespaceId).isNotEmpty()
                if (bound) {
                    ToolGrantDecision.Neutral
                } else {
                    ToolGrantDecision.Deny(setOf(toolName), "this case has no active Factory result capability")
                }
            }.getOrElse {
                ToolGrantDecision.Deny(setOf(toolName), "Factory result capability could not be verified")
            }
        }

        private companion object {
            const val STEP_RESULT_TOOL = "FACTORY__submit_step_result"
            const val ASK_STEP_QUESTION_TOOL = "FACTORY__ask_step_question"
            val CAPABILITY_BOUND_TOOLS = setOf(STEP_RESULT_TOOL, ASK_STEP_QUESTION_TOOL)
        }
    }
