package io.whozoss.agentos.plugins.kotlin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.ToolSchema
import io.whozoss.agentos.sdk.tool.ToolTimeout

/** Texts shared by the Kotlin LSP tools. */
internal object KotlinToolTexts {
    /** First sentence of every navigation tool: what makes the agent pick it over a text search. */
    const val PREFER_OVER_TEXT_SEARCH =
        "Kotlin symbols: ALWAYS try this first, before a text search (searchFiles, grep). " +
            "Fall back to a text search only if no result or timeout."

    const val SYMBOL_PARAM =
        "Name of the Kotlin symbol (class, function, property...), e.g. 'AgentLoader'. " +
            "Use 'AgentLoader.load' to tell apart symbols sharing a name."

    const val FILE_PARAM =
        "Optional. Project relative path of the Kotlin file. Alone: the symbol declared in that file, else its first usage there."

    const val LINE_PARAM =
        "Optional, requires 'file'. 1-based line number, as shown by grep and by the file read tools. " +
            "Targets the symbol as used on that line."

    const val SOURCE_FILE_PARAM = "Project relative path of the Kotlin source file."

    const val ZERO_BASED = "0-based, as the LSP protocol"

    private const val ON_EXPIRY = "the Kotlin LSP request is cancelled"

    val TIMEOUT_PARAM_DESCRIPTION: String =
        ToolTimeout.paramDescription(onExpiry = ON_EXPIRY) +
            "The first call on a project starts the language server, which then indexes it: give that call a large value " +
            "(e.g. 120); about 30 is enough afterwards."

    /** Message returned when the agent did not provide `timeoutSeconds` at all. */
    val MISSING_TIMEOUT_MESSAGE: String = ToolTimeout.missingMessage(onExpiry = ON_EXPIRY)
}

/** The description of a tool, one sentence per line. */
internal fun describe(vararg sentences: String): String = sentences.joinToString(separator = "\n")

/** Input schemas of the Kotlin LSP tools, built on [ToolSchema]: every one of them requires `timeoutSeconds`. */
internal object KotlinSchemas {
    private val mapper = jacksonObjectMapper()

    private const val TIMEOUT = "timeoutSeconds"

    /** A `boolean` property, which [ToolSchema] does not provide. */
    fun boolean(description: String): ObjectNode = mapper.createObjectNode().put("type", "boolean").put("description", description)

    /** The input schema of a tool: [properties] and the mandatory `timeoutSeconds`, [required] ones being mandatory too. */
    fun of(
        required: List<String>,
        properties: Map<String, JsonNode>,
    ): String =
        ToolSchema.schema(
            required = required + TIMEOUT,
            properties = properties + (TIMEOUT to ToolSchema.integer(description = KotlinToolTexts.TIMEOUT_PARAM_DESCRIPTION)),
        )

    /** The input schema of a tool addressing a symbol by name, with the properties it adds. */
    fun ofSymbol(
        extraProperties: Map<String, JsonNode> = emptyMap(),
        extraRequired: List<String> = emptyList(),
    ): String =
        of(
            required = listOf("symbol") + extraRequired,
            properties =
                mapOf<String, JsonNode>(
                    "symbol" to ToolSchema.string(description = KotlinToolTexts.SYMBOL_PARAM),
                    "file" to ToolSchema.string(description = KotlinToolTexts.FILE_PARAM),
                    "line" to ToolSchema.integer(description = KotlinToolTexts.LINE_PARAM),
                ) + extraProperties,
        )
}
