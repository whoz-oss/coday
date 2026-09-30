package io.whozoss.agentos.plugins.http

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.config.ResponseFormat
import io.whozoss.agentos.plugins.http.config.SpecConfig

/**
 * JSON Schema of the `HTTP_API` integration config, rendered as a form by the admin UI.
 *
 * It declares exactly the keys of [HttpApiConfig]: the form drops any key the schema does not declare, so a
 * config edited in the UI would silently lose an undeclared key; every object refuses additional properties,
 * as [io.whozoss.agentos.plugins.http.config.HttpApiConfigParser] does. Defaults are the Kotlin defaults of the
 * config classes (a unit test keeps both in sync). Built with Jackson nodes rather than a JSON text so
 * that every default references the constant it documents.
 */
object HttpApiConfigSchema {

    private val nodes = JsonNodeFactory.instance

    val schema: JsonNode = objectSchema(
        title = "HTTP API integration",
        description = "Exposes the operations of an OpenAPI 3.x described HTTP API as agent tools. " +
            "Authentication comes from the bound Auth Setting.",
        required = listOf("spec"),
        additionalProperties = false,
    ) {
        set<JsonNode>("spec", specSchema())
        set<JsonNode>(
            "baseUrl",
            property(
                "string",
                title = "Base URL",
                description = "Public https URL every operation path is appended to, e.g. https://corp.zendesk.com. " +
                    "Optional when the document declares an absolute servers[0].url (variables replaced by their " +
                    "defaults), which it overrides. Private and local addresses are refused.",
            ).put("format", "uri"),
        )
        set<JsonNode>(
            "includeTags",
            stringList(
                title = "Include tags",
                description = "Keep only operations carrying at least one of these OpenAPI tags. " +
                    "Empty keeps every operation.",
            ),
        )
        set<JsonNode>(
            "includePathPrefixes",
            stringList(
                title = "Include path prefixes",
                description = "Keep only operations whose path starts with one of these prefixes, " +
                    "e.g. /api/v2/tickets. Empty keeps every operation.",
            ),
        )
        set<JsonNode>(
            "includeOperations",
            stringList(
                title = "Include operations",
                description = "Keep only operations whose operationId matches one of these patterns " +
                    "(* and ? wildcards). Empty keeps every operation.",
            ),
        )
        set<JsonNode>(
            "excludeOperations",
            stringList(
                title = "Exclude operations",
                description = "Drop operations whose operationId matches one of these patterns " +
                    "(* and ? wildcards), e.g. *Bulk*.",
            ),
        )
        set<JsonNode>(
            "maxTools",
            property(
                "integer",
                title = "Maximum number of tools",
                description = "The integration exposes no tools at all when more operations than this remain " +
                    "after filtering: narrow the selection instead.",
            )
                .put("default", HttpApiConfig.DEFAULT_MAX_TOOLS)
                .put("minimum", 1)
                .put("maximum", HttpApiConfig.MAX_TOOLS_UPPER_BOUND),
        )
        set<JsonNode>("operations", operationsSchema())
        set<JsonNode>("auth", authSchema())
        set<JsonNode>(
            "defaultHeaders",
            property(
                "object",
                title = "Default headers",
                description = "Static, non-secret headers added to every call (Authorization, " +
                    "Proxy-Authorization, Cookie and Host are refused; a name equal to the effective API key " +
                    "header fails the document load). The agent cannot change them.",
            ).apply { putObject("additionalProperties").put("type", "string") },
        )
        set<JsonNode>(
            "responseFormat",
            responseFormat(
                description = "How responses are rendered for the agent: compact JSON or YAML " +
                    "(more readable, fewer tokens).",
            ).put("default", HttpApiConfig.DEFAULT_RESPONSE_FORMAT.name.lowercase()),
        )
        set<JsonNode>(
            "maxResponseChars",
            maxResponseChars(
                description = "Responses longer than this are cut with a marker telling the agent to narrow " +
                    "the request.",
            ).put("default", HttpApiConfig.DEFAULT_MAX_RESPONSE_CHARS),
        )
        set<JsonNode>(
            "timeoutSeconds",
            property(
                "integer",
                title = "Call timeout (seconds)",
                description = "Maximum duration of one call once it holds a concurrency slot; the wait for a " +
                    "slot is bounded by the same value.",
            )
                .put("default", HttpApiConfig.DEFAULT_TIMEOUT_SECONDS)
                .put("minimum", 1),
        )
        set<JsonNode>(
            "maxConcurrentCalls",
            property(
                "integer",
                title = "Maximum concurrent calls",
                description = "Simultaneous calls allowed for this integration config across all agents and " +
                    "runs of the service; a call waits for a free slot up to the call timeout.",
            )
                .put("default", HttpApiConfig.DEFAULT_MAX_CONCURRENT_CALLS)
                .put("minimum", 1),
        )
        set<JsonNode>(
            "allowMutations",
            property(
                "boolean",
                title = "Allow mutations",
                description = "Also expose POST, PUT, PATCH and DELETE operations as [WRITE] tools. Only the " +
                    "advanced agent asks the user to confirm a write: a simple agent executes writes without " +
                    "confirmation, so restrict [WRITE] tools with a per-agent tool allowlist.",
            ).put("default", HttpApiConfig.DEFAULT_ALLOW_MUTATIONS),
        )
    }

    private fun specSchema(): ObjectNode =
        objectSchema(
            title = "OpenAPI document",
            description = "Where the OpenAPI 3.x document comes from: a public https URL, the document text " +
                "itself, or a file on the service host. Set exactly one of URL, inline and file.",
            additionalProperties = false,
        ) {
            set<JsonNode>(
                "url",
                property(
                    "string",
                    title = "Document URL",
                    description = "Public https URL of the OpenAPI document (JSON or YAML), fetched without " +
                        "authentication.",
                ).put("format", "uri"),
            )
            set<JsonNode>(
                "inline",
                property(
                    "string",
                    title = "Inline document",
                    description = "The OpenAPI 3.x document itself (JSON or YAML), for APIs without a published " +
                        "document.",
                ).put(UI_WIDGET, TEXTAREA),
            )
            set<JsonNode>(
                "file",
                property(
                    "string",
                    title = "Spec file",
                    description = "Absolute path of the OpenAPI document (.json, .yaml or .yml) on the service " +
                        "host, typically written as {{NAMESPACE_CONFIG_PATH}}/specs/<name>.yaml in a filesystem " +
                        "integration config (the service substitutes the token). Reloaded when the file changes.",
                ),
            )
            set<JsonNode>(
                "refreshMinutes",
                property(
                    "integer",
                    title = "Refresh interval (minutes)",
                    description = "How often a document fetched from a URL is checked for changes; 0 keeps the " +
                        "first fetched document until the service restarts.",
                )
                    .put("default", SpecConfig.DEFAULT_REFRESH_MINUTES)
                    .put("minimum", 0),
            )
            set<JsonNode>(
                "maxBytes",
                property(
                    "integer",
                    title = "Maximum document size (bytes)",
                    description = "Documents larger than this are refused.",
                )
                    .put("default", SpecConfig.DEFAULT_MAX_BYTES)
                    .put("minimum", SpecConfig.MIN_MAX_BYTES),
            )
        }

    private fun operationsSchema(): ObjectNode =
        property(
            "array",
            title = "Per-operation settings",
            description = "Overrides for individual operations, identified by their operationId.",
        ).apply {
            set<JsonNode>(
                "items",
                objectSchema(
                    title = "Operation",
                    description = "Settings of one operation.",
                    required = listOf("operationId"),
                    additionalProperties = false,
                ) {
                    set<JsonNode>(
                        "operationId",
                        property(
                            "string",
                            title = "Operation id",
                            description = "The operationId of the OpenAPI operation these settings apply to.",
                        ),
                    )
                    set<JsonNode>(
                        "description",
                        property(
                            "string",
                            title = "Description",
                            description = "Replaces the description derived from the document, to tell the agent " +
                                "when to use this operation.",
                        ).put(UI_WIDGET, TEXTAREA),
                    )
                    set<JsonNode>(
                        "keepPaths",
                        stringList(
                            title = "Keep paths",
                            description = "Dot-notation paths kept in the response, e.g. results.*.id; everything " +
                                "else is dropped. Takes precedence over ignore paths.",
                        ),
                    )
                    set<JsonNode>(
                        "ignorePaths",
                        stringList(
                            title = "Ignore paths",
                            description = "Dot-notation paths removed from the response, e.g. ticket.custom_fields.",
                        ),
                    )
                    set<JsonNode>(
                        "responseFormat",
                        responseFormat(
                            description = "How this operation's responses are rendered for the agent; defaults to " +
                                "the integration setting.",
                        ),
                    )
                    set<JsonNode>(
                        "maxResponseChars",
                        maxResponseChars(
                            description = "Cap on this operation's rendered response; defaults to the integration " +
                                "setting.",
                        ),
                    )
                },
            )
        }

    private fun authSchema(): ObjectNode =
        objectSchema(
            title = "API key placement",
            description = "How an API key from the bound Auth Setting is sent. Ignored for bearer, basic and " +
                "OAuth Auth Settings, which always use the Authorization header. When the whole block is left at " +
                "its defaults, an apiKey entry of the document's components.securitySchemes (header or query) " +
                "decides the placement and the name.",
            additionalProperties = false,
        ) {
            set<JsonNode>(
                "apiKeyIn",
                property(
                    "string",
                    title = "Send the API key as",
                    description = "A named header, a named query parameter, or an Authorization: Bearer header.",
                )
                    .enumOf(ApiKeyPlacement.entries)
                    .put("default", AuthConfig.DEFAULT_API_KEY_PLACEMENT.name.lowercase()),
            )
            set<JsonNode>(
                "apiKeyName",
                property(
                    "string",
                    title = "Header or query parameter name",
                    description = "Name of the header or query parameter carrying the API key.",
                ).put("default", AuthConfig.DEFAULT_API_KEY_NAME),
            )
        }

    private fun objectSchema(
        title: String,
        description: String,
        required: List<String> = emptyList(),
        additionalProperties: Boolean? = null,
        properties: ObjectNode.() -> Unit,
    ): ObjectNode =
        property("object", title = title, description = description).apply {
            putObject("properties").apply(properties)
            if (required.isNotEmpty()) putArray("required").also { array -> required.forEach { array.add(it) } }
            additionalProperties?.let { put("additionalProperties", it) }
        }

    private fun stringList(title: String, description: String): ObjectNode =
        property("array", title = title, description = description).apply { putObject("items").put("type", "string") }

    private fun responseFormat(description: String): ObjectNode =
        property("string", title = "Response format", description = description).enumOf(ResponseFormat.entries)

    /** Declares the lowercase names of [values] as the `enum` of this property, the spelling the parser accepts. */
    private fun ObjectNode.enumOf(values: List<Enum<*>>): ObjectNode =
        apply { putArray("enum").also { enum -> values.forEach { enum.add(it.name.lowercase()) } } }

    private fun maxResponseChars(description: String): ObjectNode =
        property("integer", title = "Maximum response length (characters)", description = description)
            .put("minimum", HttpApiConfig.MIN_RESPONSE_CHARS)

    private fun property(type: String, title: String, description: String): ObjectNode =
        nodes.objectNode().put("type", type).put("title", title).put("description", description)

    private const val UI_WIDGET = "x-ui-widget"
    private const val TEXTAREA = "textarea"
}
