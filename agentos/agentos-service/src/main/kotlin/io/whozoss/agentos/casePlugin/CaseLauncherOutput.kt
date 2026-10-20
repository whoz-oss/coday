package io.whozoss.agentos.casePlugin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.UUID

/**
 * The whole output of the launcher tools ([FreeCaseLauncherTool], [FreeCaseLauncherForUserTool]), as [SCHEMA]
 * declares it: the id of the case they started. A caller that reads the structured output (AgentLoop) parses it
 * back into this class, whichever launcher it called.
 */
data class CaseLauncherOutput(
    val caseId: UUID,
) {
    companion object {
        /** The `outputSchema` of both launcher tools. */
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
