package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

/**
 * The whole output of the starter tools ([FreeCaseStarterTool], [FreeCaseStarterForUserTool]), as [SCHEMA]
 * declares it: the id of the case they started. A caller that reads the structured output (AgentLoop) parses it
 * back into this class, whichever starter it called.
 */
data class CaseStarterOutput(
    val caseId: UUID,
) {
    companion object {
        /** The `outputSchema` of both starter tools. */
        val SCHEMA: JsonNode =
            jacksonObjectMapper().readTree(
                """
                {
                  "type": "object",
                  "properties": {
                    "caseId": { "type": "string", "format": "uuid", "description": "Id of the case that was started." }
                  },
                  "required": ["caseId"]
                }
                """.trimIndent(),
            )
    }
}
