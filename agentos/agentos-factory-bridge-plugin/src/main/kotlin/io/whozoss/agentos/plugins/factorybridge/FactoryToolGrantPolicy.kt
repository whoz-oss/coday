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
 * Every other tool is left untouched ([ToolGrantDecision.Neutral]), matching the SPI's
 * pass-through contract.
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
            if (toolName != STEP_RESULT_TOOL) return ToolGrantDecision.Neutral
            val caseId = context.caseEvents.map { it.caseId }.distinct().singleOrNull()
                ?: return ToolGrantDecision.Deny(setOf(toolName), "no single controlling case")
            val bound = services().stepResultBindings.contextForCase(caseId, context.namespaceId).isNotEmpty()
            return if (bound) {
                ToolGrantDecision.Neutral
            } else {
                ToolGrantDecision.Deny(setOf(toolName), "this case has no active Factory result capability")
            }
        }

        private companion object {
            const val STEP_RESULT_TOOL = "FACTORY__submit_step_result"
        }
    }
