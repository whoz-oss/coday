package io.whozoss.agentos.configAgent

import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationTypeDescriptor

/**
 * The built-in `AGENTOS_CONFIG` integration: the tools that let an agent read the configuration
 * the user can see and propose changes to it (#1477).
 *
 * Built-in like the file exchange: there is no `IntegrationConfig` instance. An agent enables it by
 * naming [TYPE] in its `integrations` map, with the usual value semantics (null for every tool, a
 * list for some of them, an empty list to opt out). It is never granted to an agent that does not
 * name it: [descriptor] declares `enabledByDefault = false` and no platform property can change it.
 *
 * The agent never writes. `propose` freezes a plan and suspends the run on a question; the plan is
 * applied by `ConfigChangeController` inside the HTTP request of the user who clicks Apply.
 */
object ConfigAgentIntegration {
    const val TYPE = "AGENTOS_CONFIG"
    const val READ_TOOL = "read"
    const val PROPOSE_TOOL = "propose"
    const val READ_TOOL_NAME = "${TYPE}__$READ_TOOL"
    const val PROPOSE_TOOL_NAME = "${TYPE}__$PROPOSE_TOOL"

    /** `schema` field of a frozen plan, so a reader can tell it from another structured output. */
    const val PLAN_SCHEMA = "agentos.config-change/1"

    fun descriptor(): IntegrationTypeDescriptor =
        IntegrationTypeDescriptor(
            type = TYPE,
            displayName = "AgentOS configuration",
            description =
                "Lets the agent read the AgentOS configuration you can see and propose changes " +
                    "(agents, prompts, skills, integrations, API-key auth settings, deployment). " +
                    "Nothing is applied until you review the plan and click Apply.",
            configSchema = null,
            builtIn = true,
            enabledByDefault = false,
        )
}
