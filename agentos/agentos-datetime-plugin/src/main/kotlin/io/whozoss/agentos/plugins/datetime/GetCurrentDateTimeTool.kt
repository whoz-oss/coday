package io.whozoss.agentos.plugins.datetime

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.StandardTool
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Tool that returns the current date and time in a specified timezone.
 *
 * [defaultTimezone] is injected at construction time from the plugin's
 * IntegrationConfig parameters. The LLM can still override it per-call
 * by supplying an explicit [Input.timezone] value.
 */
class GetCurrentDateTimeTool(
    private val defaultTimezone: String = "UTC",
) : StandardTool<GetCurrentDateTimeTool.Input> {
    companion object {
        private val objectMapper = jacksonObjectMapper()
    }

    override val name: String = "GetCurrentDateTime"

    override val description: String =
        """
        Get the current date and time in a specified timezone.
        Returns ISO-8601 formatted datetime string with timezone information.
        The 'timezone' parameter is optional: if omitted, the configured default ($defaultTimezone) is used.
        When the user mentions a specific city or region, pass the matching IANA timezone ID
        (e.g. 'America/New_York', 'Europe/Paris', 'Asia/Tokyo', 'UTC').
        """.trimIndent()

    override val version: String = "1.0.0"

    override val paramType: Class<Input> = Input::class.java

    // language=JSON
    override val inputSchema: String =
        """
        {
            "${"\$"}schema": "https://json-schema.org/draft/2020-12/schema",
            "type": "object",
            "properties": {
                "timezone": {
                    "type": "string",
                    "description": "Optional IANA timezone identifier (e.g. 'America/New_York', 'Europe/Paris', 'UTC'). Defaults to $defaultTimezone if omitted.",
                    "default": "$defaultTimezone"
                }
            },
            "additionalProperties": false
        }
        """.trimIndent()

    data class Input(
        val timezone: String? = null,
    )

    override fun execute(input: Input?): String {
        val timezone = input?.timezone?.takeIf { it.isNotBlank() } ?: defaultTimezone

        return try {
            val zoneId =
                try {
                    ZoneId.of(timezone)
                } catch (e: Exception) {
                    return createErrorResponse(
                        "Invalid timezone: $timezone. Use standard timezone IDs like 'America/New_York' or 'UTC'",
                    )
                }

            val now = ZonedDateTime.now(zoneId)
            val formatted = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            objectMapper.writeValueAsString(
                mapOf(
                    "success" to true,
                    "datetime" to formatted,
                    "timezone" to timezone,
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
