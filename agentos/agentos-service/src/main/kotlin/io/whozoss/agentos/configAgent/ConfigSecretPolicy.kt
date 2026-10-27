package io.whozoss.agentos.configAgent

import com.fasterxml.jackson.databind.JsonNode

/**
 * Where the config agent's secrets may live, in one place for the planner (what a plan may declare
 * or write) and the masker (what a read hides).
 *
 * The planner's checks are a deny-list: a credential written in a free-text field (instructions, a
 * prompt, a description) or in a parameter with an unexpected name cannot be told from plain text.
 * The card shows every proposed value before Apply, and the documentation says so.
 */
internal object ConfigSecretPolicy {
    /** The secret fields of the auth setting types: masked on read, and the only auth setting fields a plan may declare as secrets. */
    val AUTH_SETTING_SECRET_FIELDS = setOf("apiKey", "token", "password", "clientSecret")

    /** Integration parameter keys a read shows in clear, compared in lower case: they only locate a service. */
    val NON_SECRET_PARAMETER_KEYS =
        setOf("baseurl", "url", "specurl", "openapiurl", "serverurl", "endpoint", "apibaseurl", "mcpurl", "description", "title", "region")

    /** Integration parameter keys that hold a credential, compared in lower case: a plan may not write them in clear. */
    private val SECRET_PARAMETER_KEYS =
        setOf(
            "token",
            "secret",
            "password",
            "passphrase",
            "apikey",
            "api_key",
            "clientsecret",
            "client_secret",
            "accesstoken",
            "access_token",
            "refreshtoken",
            "refresh_token",
            "bearer",
            "authorization",
            "privatekey",
            "private_key",
            "webhooksecret",
            "webhook_secret",
        )

    private const val PARAMETERS = "parameters"

    /** Whether a plan may declare [path] as a secret of a [kind]: a credential field of an auth setting, a parameter of an integration. */
    fun canHoldSecret(
        kind: ConfigKind,
        path: String,
    ): Boolean =
        when (kind) {
            ConfigKind.AUTH_SETTING -> ConfigJson.segments(path).let { it.size == 1 && it.single() in AUTH_SETTING_SECRET_FIELDS }
            ConfigKind.INTEGRATION -> ConfigJson.segments(path).let { it.size > 1 && it.first() == PARAMETERS }
            else -> false
        }

    fun secretFieldsHint(kind: ConfigKind): String =
        when (kind) {
            ConfigKind.AUTH_SETTING -> "use one of ${AUTH_SETTING_SECRET_FIELDS.joinToString { "/$it" }}"
            ConfigKind.INTEGRATION -> "use a path under /$PARAMETERS/"
            else -> "this kind has no secret field"
        }

    /** Errors for the declared [secrets] that are not secret fields of [authType], whose typed value its DTO would drop. */
    fun misplacedAuthSettingSecrets(
        authType: String?,
        secrets: List<SecretSlot>,
    ): List<String> {
        val allowed = ConfigAuthSettingTypes.secretFieldsOf(authType) ?: return emptyList()
        return secrets.filter { ConfigJson.segments(it.path).first() !in allowed }.map {
            "'${it.path}' is not a secret field of a $authType auth setting: " +
                if (allowed.isEmpty()) "it has none." else "use ${allowed.joinToString { field -> "/$field" }}."
        }
    }

    /**
     * Pointers, under [path], where [node] writes a secret in clear: a secret field of an auth setting
     * (of its type, or of any type when the type is unknown), or an integration parameter named like a
     * credential. A secret is declared in `secrets` and typed by the user, never written by the LLM.
     */
    fun clearTextSecrets(
        kind: ConfigKind,
        authType: String?,
        node: JsonNode?,
        path: String,
    ): List<String> =
        when (kind) {
            ConfigKind.AUTH_SETTING -> {
                val fields = ConfigAuthSettingTypes.secretFieldsOf(authType) ?: AUTH_SETTING_SECRET_FIELDS
                secretLeaves(node, path) { pointer -> ConfigJson.segments(pointer).let { it.size == 1 && it.single() in fields } }
            }
            ConfigKind.INTEGRATION ->
                secretLeaves(node, path) { pointer ->
                    ConfigJson.segments(pointer).let { it.size > 1 && it.first() == PARAMETERS && it.last().lowercase() in SECRET_PARAMETER_KEYS }
                }
            else -> emptyList()
        }

    fun clearTextSecretError(pointer: String): String = "'$pointer' holds a secret: never write it, declare it in 'secrets' so the user types it in the card."

    /** Pointers of the non-blank text leaves of [node], rooted at [path], that [isSecret] flags. */
    private fun secretLeaves(
        node: JsonNode?,
        path: String,
        isSecret: (String) -> Boolean,
    ): List<String> =
        when {
            node == null -> emptyList()
            node.isObject -> node.properties().flatMap { (key, value) -> secretLeaves(value, path + ConfigJson.pointerOf(listOf(key)), isSecret) }
            node.isArray -> node.flatMapIndexed { index, value -> secretLeaves(value, "$path/$index", isSecret) }
            node.isTextual && node.textValue().isNotBlank() && path.isNotEmpty() && isSecret(path) -> listOf(path)
            else -> emptyList()
        }
}
