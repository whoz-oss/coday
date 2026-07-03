package io.whozoss.agentos.plugins.datetime

import io.whozoss.agentos.sdk.tool.StandardTool
import io.whozoss.agentos.sdk.tool.ToolContext
import io.whozoss.agentos.sdk.tool.ToolExecutionResult
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Tool that returns the current date and time in a specified timezone.
 *
 * [defaultTimezone] is injected at construction time from the plugin's
 * IntegrationConfig parameters. The AI can still override it per-call
 * by supplying an explicit [Input.timezone] value.
 */
class GetCurrentDateTimeTool(
    private val defaultTimezone: String = "UTC",
    configName: String? = null,
) : StandardTool<GetCurrentDateTimeTool.Input> {
    override val name: String = if (configName != null) "${configName}__GetCurrentDateTime" else "GetCurrentDateTime"

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

    override suspend fun execute(
        input: Input?,
        context: ToolContext,
    ): ToolExecutionResult {
        val timezone = input?.timezone?.takeIf { it.isNotBlank() } ?: defaultTimezone

        val zoneId =
            try {
                ZoneId.of(timezone)
            } catch (e: Exception) {
                return ToolExecutionResult.error(
                    output = "Invalid timezone: $timezone. Use standard timezone IDs like 'America/New_York' or 'UTC'",
                    errorType = "INVALID_TIMEZONE",
                    errorMessage = e.message,
                )
            }

        return try {
            val now = ZonedDateTime.now(zoneId)
            val formatted = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

            ToolExecutionResult.success(
                output = formatted,
                metadata =
                    mapOf(
                        "timezone" to timezone,
                        "offset" to now.offset.toString(),
                        "epochSecond" to now.toEpochSecond(),
                    ),
            )
        } catch (e: Exception) {
            ToolExecutionResult.error(
                output = "Error getting datetime: ${e.message}",
                errorType = "EXECUTION_ERROR",
                errorMessage = e.message,
            )
        }
    }
}
