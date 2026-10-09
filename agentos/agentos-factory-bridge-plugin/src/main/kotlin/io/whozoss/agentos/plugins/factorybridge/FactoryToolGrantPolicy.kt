package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.spi.ToolGrantDecision
import io.whozoss.agentos.sdk.spi.ToolGrantPolicy
import io.whozoss.agentos.sdk.tool.ToolContext
import org.pf4j.Extension

/**
 * Leaves Factory tool exposure to the standard IntegrationConfig / AgentConfig resolver.
 *
 * Worker binding validation belongs to tool invocation, where the trusted case, namespace,
 * agent, capability, active-attempt and single-use checks can be evaluated together. Hiding
 * worker tools here when no binding exists would incorrectly make transient runtime state
 * decide the model's configured tool surface.
 */
@Extension
class FactoryToolGrantPolicy
    @JvmOverloads
    constructor(
        @Suppress("UNUSED_PARAMETER") services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
    ) : ToolGrantPolicy {
    override fun evaluateToolGrant(
        toolName: String,
        context: ToolContext,
    ): ToolGrantDecision = ToolGrantDecision.Neutral
}
