package io.whozoss.agentos.integrationConfig

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.stereotype.Component

/**
 * Fallback catalogue of hardcoded [IntegrationTypeDescriptor]s for integration types
 * that have no loaded plugin yet (JIRA, GITHUB, SLACK).
 *
 * This class is NOT the primary [IntegrationTypeRegistry] implementation — that role
 * belongs to [CompositeIntegrationTypeRegistry], which merges these hardcoded entries
 * with plugin-contributed ones. Plugin-contributed descriptors always take precedence
 * over hardcoded ones for the same type.
 *
 * The JIRA schema is intentionally rich to exercise every form-field case the client
 * must handle: required vs optional scalars, nested objects, arrays, enums, format hints.
 */
@Component
class HardcodedIntegrationTypeRegistry(
    private val objectMapper: ObjectMapper,
) {

    private val descriptors: List<IntegrationTypeDescriptor> by lazy { buildDescriptors() }

    fun listTypes(): List<IntegrationTypeDescriptor> = descriptors

    fun findByType(type: String): IntegrationTypeDescriptor? =
        descriptors.firstOrNull { it.type == type }

    private fun buildDescriptors(): List<IntegrationTypeDescriptor> =
        listOf(buildJira(), buildGitHub(), buildSlack()).sortedBy { it.type }

    private fun buildJira() = IntegrationTypeDescriptor(
        type = "JIRA",
        displayName = "Jira",
        description = "Atlassian Jira - issue tracking and project management.",
        configSchema = jiraSchema(),
    )

    private fun buildGitHub() = IntegrationTypeDescriptor(
        type = "GITHUB",
        displayName = "GitHub",
        description = "GitHub - source control, pull requests and issue tracking.",
        configSchema = githubSchema(),
    )

    private fun buildSlack() = IntegrationTypeDescriptor(
        type = "SLACK",
        displayName = "Slack",
        description = "Slack - team messaging and notifications.",
        configSchema = slackSchema(),
    )

    // --- helpers ---

    private fun obj(): ObjectNode = objectMapper.createObjectNode()

    private fun arr(vararg values: String) =
        objectMapper.createArrayNode().also { arr -> values.forEach(arr::add) }

    private fun stringProp(title: String, description: String, format: String? = null): ObjectNode =
        obj().apply {
            put("type", "string")
            put("title", title)
            put("description", description)
            format?.let { put("format", it) }
        }

    private fun intProp(
        title: String,
        description: String,
        default: Int? = null,
        minimum: Int? = null,
        maximum: Int? = null,
    ): ObjectNode = obj().apply {
        put("type", "integer")
        put("title", title)
        put("description", description)
        default?.let { put("default", it) }
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
    }

    private fun boolProp(title: String, description: String, default: Boolean): ObjectNode =
        obj().apply {
            put("type", "boolean")
            put("title", title)
            put("description", description)
            put("default", default)
        }

    private fun enumProp(
        title: String,
        description: String,
        values: List<String>,
        default: String? = null,
    ): ObjectNode = obj().apply {
        put("type", "string")
        put("title", title)
        put("description", description)
        set<JsonNode>("enum", objectMapper.createArrayNode().also { a -> values.forEach(a::add) })
        default?.let { put("default", it) }
    }

    private fun rootSchema(title: String, required: List<String>, properties: ObjectNode): ObjectNode =
        obj().apply {
            put("type", "object")
            put("title", title)
            put("additionalProperties", false)
            set<JsonNode>("required", arr(*required.toTypedArray()))
            set<JsonNode>("properties", properties)
        }

    private fun nestedObjectProp(
        title: String,
        description: String,
        required: List<String>,
        properties: ObjectNode,
    ): ObjectNode = obj().apply {
        put("type", "object")
        put("title", title)
        put("description", description)
        put("additionalProperties", false)
        set<JsonNode>("required", arr(*required.toTypedArray()))
        set<JsonNode>("properties", properties)
    }

    private fun arrayOfObjectsProp(
        title: String,
        description: String,
        itemTitle: String,
        itemRequired: List<String>,
        itemProperties: ObjectNode,
    ): ObjectNode = obj().apply {
        put("type", "array")
        put("title", title)
        put("description", description)
        set<JsonNode>("items", obj().apply {
            put("type", "object")
            put("title", itemTitle)
            put("additionalProperties", false)
            set<JsonNode>("required", arr(*itemRequired.toTypedArray()))
            set<JsonNode>("properties", itemProperties)
        })
    }

    private fun arrayOfStringsProp(title: String, description: String): ObjectNode =
        obj().apply {
            put("type", "array")
            put("title", title)
            put("description", description)
            set<JsonNode>("items", obj().apply { put("type", "string") })
        }

    // --- concrete schemas ---

    private fun jiraSchema(): JsonNode {
        val proxyProps = obj().apply {
            set<JsonNode>("host", stringProp("Proxy Host", "Hostname or IP address of the proxy server."))
            set<JsonNode>("port", intProp("Proxy Port", "Port number.", minimum = 1, maximum = 65535))
            set<JsonNode>("username", stringProp("Proxy Username", "Optional username for proxy authentication."))
            set<JsonNode>("password", stringProp("Proxy Password", "Optional password.", format = "password"))
        }
        val props = obj().apply {
            set<JsonNode>("apiUrl", stringProp("API URL", "Base URL of your Jira instance.", format = "uri"))
            set<JsonNode>("apiToken", stringProp("API Token", "Personal API token from Atlassian account settings.", format = "password"))
            set<JsonNode>("userEmail", stringProp("User Email", "Email address associated with the API token.", format = "email"))
            set<JsonNode>("maxResults", intProp("Max Results", "Maximum issues per query.", default = 50, minimum = 1, maximum = 1000))
            set<JsonNode>("connectionTimeoutSeconds", intProp("Connection Timeout (s)", "HTTP connection timeout.", default = 30, minimum = 1))
            set<JsonNode>("verifySSL", boolProp("Verify SSL", "Validate the server SSL certificate (recommended).", true))
            set<JsonNode>("logLevel", enumProp("Log Level", "Verbosity of integration logs.", listOf("DEBUG", "INFO", "WARN", "ERROR"), "INFO"))
            set<JsonNode>("proxy", nestedObjectProp("Proxy Settings", "Optional HTTP proxy for outbound requests.", listOf("host", "port"), proxyProps))
            set<JsonNode>("projects", arrayOfStringsProp("Project Keys", "Jira project keys to synchronise (e.g. PROJ, DEV). Leave empty for all."))
        }
        return rootSchema("Jira Configuration", listOf("apiUrl", "apiToken", "userEmail"), props)
    }

    private fun githubSchema(): JsonNode {
        val props = obj().apply {
            set<JsonNode>("personalAccessToken", stringProp("Personal Access Token", "GitHub PAT with required scopes.", format = "password"))
            set<JsonNode>("organization", stringProp("Organization", "GitHub organization or user account (e.g. whoz-oss)."))
            set<JsonNode>("apiBaseUrl", stringProp("API Base URL", "Override for GitHub Enterprise (default: https://api.github.com).", format = "uri"))
            set<JsonNode>("defaultBranch", stringProp("Default Branch", "Base branch for new pull requests (default: main)."))
        }
        return rootSchema("GitHub Configuration", listOf("personalAccessToken", "organization"), props)
    }

    private fun slackSchema(): JsonNode {
        val props = obj().apply {
            set<JsonNode>("botToken", stringProp("Bot Token", "Slack bot OAuth token (starts with xoxb-).", format = "password"))
            set<JsonNode>("defaultChannel", stringProp("Default Channel", "Channel used when none is specified (e.g. #general)."))
            set<JsonNode>("notifyOnError", boolProp("Notify on Error", "Send a Slack message when an agent run fails.", false))
        }
        return rootSchema("Slack Configuration", listOf("botToken"), props)
    }
}
