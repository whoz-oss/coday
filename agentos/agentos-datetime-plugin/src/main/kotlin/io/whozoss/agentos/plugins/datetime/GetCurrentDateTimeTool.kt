package io.whozoss.agentos.plugins.datetime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Simple tool that returns the current date and time in a specified timezone.
 * This is a test/example tool to demonstrate the plugin system.
 */
class GetCurrentDateTimeTool : StandardTool<GetCurrentDateTimeTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
    }
    override val name: String = "GetCurrentDateTime"

    override val description: String = """
        Get the current date and time in a specified timezone.
        Returns ISO-8601 formatted datetime string with timezone information.
    """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<*> = Input::class.java

    override val inputSchema: String =
        """
        {
            "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "timezone": {
                    "type": "string",
                    "description": "Timezone identifier (e.g., 'America/New_York', 'Europe/Paris', 'UTC')",
                    "default": "UTC"
                }
            },
            "required": [],
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val timezone: String = "UTC",
    )

    override fun execute(input: Input?): String {
        val params = input ?: Input()

        return try {
            // Parse timezone
            val zoneId =
                try {
                    ZoneId.of(params.timezone)
                } catch (e: Exception) {
                    return createErrorResponse("Invalid timezone: ${params.timezone}. Use standard timezone IDs like 'America/New_York' or 'UTC'")
                }

            // Get current time in timezone
            val now = ZonedDateTime.now(zoneId)

            // Format as ISO-8601
            val formatted = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            // Return structured result
            objectMapper.writeValueAsString(
                mapOf(
                    "success" to true,
                    "datetime" to formatted,
                    "timezone" to params.timezone,
                    "offset" to now.offset.toString(),
                    "epochSecond" to now.toEpochSecond(),
                ),
            )
        } catch (e: Exception) {
            createErrorResponse("Error getting datetime: ${e.message}")
        }
    }

    private fun createErrorResponse(message: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "success" to false,
                "error" to message,
            ),
        )
}
