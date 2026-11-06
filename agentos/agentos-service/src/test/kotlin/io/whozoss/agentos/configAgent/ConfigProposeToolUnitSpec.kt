package io.whozoss.agentos.configAgent

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.util.UUID

/**
 * Pins the one thing the config agent can only learn from this tool: that `subAgents` is keyed by name.
 *
 * The description's field list is the whole specification the agent gets for an entity's shape, and
 * every other reference in it is an id (`agentConfigId`, `scope.namespaceId`, the `id` an UPDATE
 * targets, a `{"$op": ref}` that resolves to one). Told only the field's name, the agent writes an id
 * there, [io.whozoss.agentos.agentConfig.SubAgentSelectorValidation] refuses the write, and the user
 * finds out when their Apply fails. So the rule belongs in the description, not only in the guard.
 */
class ConfigProposeToolUnitSpec :
    StringSpec({
        "subAgents is described as keyed by agent name, with ids ruled out" {
            val description =
                ConfigProposeTool(
                    callerId = UUID.randomUUID(),
                    namespaceId = UUID.randomUUID(),
                    caseId = UUID.randomUUID(),
                    agentName = "Configurator",
                    configPlanner = mockk(),
                    objectMapper = ObjectMapper(),
                    clock = Clock.systemUTC(),
                ).description

            description shouldContain "subAgents (the names of the agents"
            description shouldContain "never an id"
        }
    })
