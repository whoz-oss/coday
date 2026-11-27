package io.whozoss.agentos.plugins.google

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.whozoss.agentos.sdk.tool.*
import io.whozoss.agentos.sdk.credential.CredentialType
import org.pf4j.Plugin
import org.pf4j.Extension

class GoogleWorkspacePlugin : Plugin() {
    override fun start() { GoogleNetwork.start() }
    override fun stop() { GoogleNetwork.stop() }
}

/** A curated GET only; no caller-controlled origin, headers, method or body. */
data class GoogleRequest(val path: String, val query: Map<String, String> = emptyMap())
class GoogleResponse(val status: Int, val contentType: String, val body: String)
fun interface GoogleTransport { fun get(request: GoogleRequest, token: String): GoogleResponse }
/**
 * [field] is a schema path built from constant keys and indexes only, [reason] a constant sentence: neither is
 * ever built from a configuration value.
 */
internal class Refusal(val code: String, val field: String? = null, val reason: String? = null) : RuntimeException(code)

/**
 * Raised by [GoogleWorkspaceToolProvider.provideTools]. Its message carries the stable code and the path of the
 * offending field (e.g. `calendar.allowedCalendarIds[0]`), never a configuration value.
 */
class GoogleConfigRefused(val code: String, val field: String? = null, val reason: String? = null) :
    IllegalArgumentException(
        "Google Workspace configuration refused: $code" + (field?.let { " at $it" } ?: "") + (reason?.let { ": $it" } ?: ""),
    ) {
    // No stack trace: nothing about the rejected configuration is worth (or safe) carrying to the host logs.
    override fun fillInStackTrace(): Throwable = this
}
internal fun requireGoverned(condition: Boolean, code: String = "POLICY_DENIED", field: String? = null, reason: String? = null) {
    if (!condition) throw Refusal(code, field, reason)
}

@Extension
class GoogleWorkspaceToolProvider @JvmOverloads constructor(
    private val transport: () -> GoogleTransport = { GoogleNetwork.transport },
) : ToolPlugin {
    override val integrationType = "GOOGLE_WORKSPACE"
    override val configSchema: JsonNode get() = GooglePolicy.schema
    /**
     * Fail-closed, but never silent: an invalid configuration exposes no tool AND throws, so the host
     * reports the integration as failed to load (instructions notice + case warning) instead of
     * presenting it as an integration that simply has no tools.
     *
     * The thrown exception carries only a stable refusal code and the schema path of the offending field,
     * with no cause: neither configuration values nor a parser message can reach the host logs, the model
     * or the case.
     */
    override fun provideTools(config: JsonNode?, configName: String?, context: ToolContext?): List<StandardTool<*>> {
        val policy = try {
            requireGoverned(configName != null && configName.matches(Regex("[A-Za-z0-9_-]{1,36}")) && !configName.contains("__"), "INVALID_CONFIG", "name",
                "the configuration name must be 1 to 36 letters, digits, _ or -, without __")
            GooglePolicy.parse(config)
        } catch (e: Exception) {
            val refusal = e as? Refusal
            throw GoogleConfigRefused(refusal?.code ?: "INVALID_CONFIG", refusal?.field, refusal?.reason)
        }
        return GoogleOperation.entries.map { GoogleTool(configName!!, it, policy, context, transport) }
    }

    override suspend fun describeNamespace(config: JsonNode?, configName: String?, context: ToolContext?): String =
        "Google Workspace: governed read-only metadata and bounded text. Default deny. No writes, attachments, threads or arbitrary HTTP calls. Other integrations are not governed by this plugin."
}

internal enum class GoogleOperation(val suffix: String, val keys: Set<String>, val required: Set<String>) {
    DRIVE_LIST("drive_list", setOf("query"), emptySet()),
    DRIVE_METADATA("drive_metadata", setOf("fileId"), setOf("fileId")),
    DRIVE_READ("drive_read", setOf("fileId"), setOf("fileId")),
    DRIVE_EXPORT("drive_export", setOf("fileId"), setOf("fileId")),
    CALENDAR_LIST("calendar_events", setOf("calendarId", "timeMin", "timeMax"), setOf("calendarId", "timeMin", "timeMax")),
    CALENDAR_GET("calendar_event", setOf("calendarId", "eventId"), setOf("calendarId", "eventId")),
    GMAIL_SEARCH("gmail_search", setOf("query"), emptySet()),
    GMAIL_METADATA("gmail_metadata", setOf("messageId"), setOf("messageId")),
    GMAIL_READ("gmail_read", setOf("messageId"), setOf("messageId")),
}

internal class GoogleTool(
    prefix: String,
    private val operation: GoogleOperation,
    private val policy: GooglePolicy,
    private val binding: ToolContext?,
    private val transport: () -> GoogleTransport,
) : StandardTool<JsonNode> {
    override val name = "${prefix}__${operation.suffix}"
    override val description = "Governed read-only ${operation.suffix}. Returns only authorized objects. No pagination tokens or raw responses. Text reads are bounded; unsupported formats and writes are refused."
    override val version = "1.0.0"
    override val paramType = JsonNode::class.java
    override val inputSchema: String = mapper.createObjectNode().apply {
        put("type", "object"); put("additionalProperties", false)
        set<JsonNode>("properties", mapper.createObjectNode().apply {
            operation.keys.forEach { key -> set<JsonNode>(key, mapper.createObjectNode().put("type", "string").put("maxLength", if (key == "query") 256 else 254)) }
        })
        set<JsonNode>("required", mapper.valueToTree(operation.required))
    }.toString()

    override suspend fun executeWithJson(json: String?, context: ToolContext): ToolExecutionResult =
        try { execute(mapper.readTree(json ?: "{}"), context) }
        catch (_: Exception) { ToolExecutionResult.error("Google Workspace request refused: INVALID_INPUT", "INVALID_INPUT") }

    override suspend fun execute(input: JsonNode?, context: ToolContext): ToolExecutionResult = try {
        policy.validate()
        requireGoverned(input != null && input.isObject, "INVALID_INPUT")
        val args = input!!
        requireGoverned(args.fieldNames().asSequence().all { it in operation.keys }, "UNSUPPORTED")
        requireGoverned(operation.required.all { args.has(it) }, "INVALID_INPUT")
        args.properties().forEach { (k, v) ->
            requireGoverned(v.isTextual && v.textValue().length in 1..(if (k == "query") 256 else 254) && v.textValue().none { it.isISOControl() }, "INVALID_INPUT")
        }
        args.properties().forEach { (k, v) -> if (k.endsWith("Id")) GooglePolicy.id(v.textValue()) }
        requireGoverned(binding?.userId != null && context.userId == binding.userId && context.namespaceId == binding.namespaceId, "AUTH_MISSING")
        val credential = binding!!.credentialProvider?.invoke()
        requireGoverned(credential != null && credential.userId == binding.userId && credential.credentialType == CredentialType.OAUTH_TOKENS, "AUTH_MISSING")
        val grant = credential!!.data["scope"]
        val scopes = grant?.split(Regex("\\s+")) ?: emptyList()
        requireGoverned(!grant.isNullOrBlank() && scopes.all { it in READ_ONLY_SCOPES }, "AUTH_MISSING")
        val token = credential.data["accessToken"]
        requireGoverned(!token.isNullOrBlank() && token.length <= 8192 && token.all { it.code in 33..126 }, "AUTH_MISSING")
        val reader = GoogleReader(policy, transport(), token!!)
        val output = reader.execute(operation, args).toString()
        requireGoverned(output.length <= policy.maxOutputChars, "LIMIT_EXCEEDED")
        // Never echo even accidentally returned credential material from a malicious upstream.
        requireGoverned(credential.data.values.filter { it.length >= 4 }.none { output.contains(it) }, "UPSTREAM_ERROR")
        ToolExecutionResult.success(output)
    } catch (e: Refusal) {
        ToolExecutionResult.error("Google Workspace request refused: ${e.code}", e.code)
    } catch (_: Exception) {
        ToolExecutionResult.error("Google Workspace request refused: UPSTREAM_ERROR", "UPSTREAM_ERROR")
    }

    companion object {
        private val mapper = jacksonObjectMapper()
        private val READ_ONLY_SCOPES = setOf(
            "https://www.googleapis.com/auth/drive.readonly",
            "https://www.googleapis.com/auth/calendar.readonly",
            "https://www.googleapis.com/auth/gmail.readonly",
        )
    }
}
