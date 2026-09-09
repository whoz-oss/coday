package io.whozoss.agentos.plugins.http.config

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidNullException
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import io.whozoss.agentos.plugins.http.net.UrlCheck
import java.net.URI
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Parses and validates a [JsonNode] config into an [HttpApiConfig].
 *
 * The UI form sends `""` for every optional field the admin left untouched: blank text fields are removed
 * from the tree before Jackson maps it, so they behave exactly like omitted fields (Kotlin default, or null
 * for a nullable field). The business rules are then checked with `require`:
 *
 * - exactly one of `spec.url` / `spec.inline` / `spec.file` is set;
 * - `spec.file` is an absolute path ending with `.json`, `.yaml` or `.yml` (the file itself is read at load time);
 * - `baseUrl` (when set: the document server URL is checked at load time otherwise) and `spec.url` pass the
 *   [OutboundUrlPolicy] static validation, and `baseUrl` carries no query string or fragment;
 * - `defaultHeaders` never carries a credential or hop-by-hop header (the effective API key header, which
 *   the document may decide, is checked at load time);
 * - `operations[].operationId` are unique;
 * - numeric fields respect their documented bounds.
 *
 * Throws [IllegalArgumentException] with a descriptive message on any violation, including a missing
 * required field or a key the config classes do not declare.
 */
object HttpApiConfigParser {

    private val mapper: ObjectMapper = JsonMapper.builder()
        .addModule(
            KotlinModule.Builder()
                // An explicit JSON null on a non-nullable list/map field means "no entries" rather than an error.
                .enable(KotlinFeature.NullToEmptyCollection)
                .enable(KotlinFeature.NullToEmptyMap)
                .build(),
        )
        // Undeclared keys are refused, as the schema's additionalProperties=false says: a mistyped key
        // (allowMutation, excludeOperation) must not silently keep the default of the intended setting.
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        // The schema advertises lowercase enum values (json, header) while the Kotlin enums are uppercase.
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
        .build()

    /**
     * @param urlPolicy policy applied to `baseUrl` and `spec.url`; the default is the production policy,
     *   a test may pass one built with `allowLoopbackForTests = true`.
     */
    fun parse(config: JsonNode, urlPolicy: OutboundUrlPolicy = OutboundUrlPolicy()): HttpApiConfig {
        val raw = try {
            mapper.treeToValue(withoutBlankFields(config), HttpApiConfig::class.java)
        } catch (e: InvalidNullException) {
            throw IllegalArgumentException("$PREFIX '${fieldPath(e)}' is required", e)
        } catch (e: UnrecognizedPropertyException) {
            throw IllegalArgumentException("$PREFIX unknown key '${fieldPath(e)}'", e)
        } catch (e: JsonProcessingException) {
            throw IllegalArgumentException("$PREFIX ${e.originalMessage}", e)
        }
        return validate(raw, urlPolicy)
    }

    /** A copy of [node] without the object fields whose value is a blank string; array elements are kept. */
    private fun withoutBlankFields(node: JsonNode): JsonNode =
        when {
            node.isObject -> JsonNodeFactory.instance.objectNode().apply {
                node.properties()
                    .filterNot { (_, value) -> value.isBlankText() }
                    .forEach { (key, value) -> set<JsonNode>(key, withoutBlankFields(value)) }
            }
            node.isArray -> JsonNodeFactory.instance.arrayNode().apply { node.forEach { add(withoutBlankFields(it)) } }
            else -> node
        }

    private fun JsonNode.isBlankText(): Boolean = isTextual && asText().isBlank()

    /** `operations[0].operationId` style path of the field a mapping error points at. */
    private fun fieldPath(e: JsonMappingException): String =
        e.path
            .joinToString("") { reference -> reference.fieldName?.let { ".$it" } ?: "[${reference.index}]" }
            .removePrefix(".")

    private fun validate(config: HttpApiConfig, urlPolicy: OutboundUrlPolicy): HttpApiConfig {
        validateSpec(config.spec, urlPolicy)
        config.baseUrl?.let { validateBaseUrl(urlPolicy, it) }
        validateHeaders(config)
        validateOperations(config.operations)
        validateBounds(config)
        return config
    }

    private fun validateSpec(spec: SpecConfig, urlPolicy: OutboundUrlPolicy) {
        val sources = listOfNotNull(spec.url, spec.inline, spec.file).size
        require(sources == 1) {
            if (sources == 0) {
                "$PREFIX exactly one of 'spec.url', 'spec.inline' or 'spec.file' is required"
            } else {
                "$PREFIX 'spec.url', 'spec.inline' and 'spec.file' are mutually exclusive - set only one"
            }
        }
        spec.url?.let { requireAllowedUrl(urlPolicy, field = "spec.url", url = it) }
        spec.file?.let(::validateSpecFile)
        require(spec.refreshMinutes >= 0) { "$PREFIX 'spec.refreshMinutes' must be >= 0, got ${spec.refreshMinutes}" }
        require(spec.maxBytes >= SpecConfig.MIN_MAX_BYTES) {
            "$PREFIX 'spec.maxBytes' must be >= ${SpecConfig.MIN_MAX_BYTES}, got ${spec.maxBytes}"
        }
    }

    private fun validateSpecFile(file: String) {
        require(SPEC_FILE_EXTENSIONS.any { file.endsWith(it, ignoreCase = true) }) {
            "$PREFIX 'spec.file' must end with .json, .yaml or .yml"
        }
        val path = try {
            Path.of(file)
        } catch (e: InvalidPathException) {
            throw IllegalArgumentException("$PREFIX 'spec.file' is not a valid path: ${e.reason}", e)
        }
        require(path.isAbsolute) {
            "$PREFIX 'spec.file' must be an absolute path (write it as {{NAMESPACE_CONFIG_PATH}}/specs/<name>.yaml " +
                "in a filesystem integration config)"
        }
    }

    /** The request URLs are built from `baseUrl` without its query and fragment: refuse them rather than drop them. */
    private fun validateBaseUrl(urlPolicy: OutboundUrlPolicy, url: String) {
        val uri = requireAllowedUrl(urlPolicy, field = "baseUrl", url = url)
        require(uri.rawQuery == null && uri.rawFragment == null) {
            "$PREFIX 'baseUrl' must not carry a query string or fragment"
        }
    }

    private fun requireAllowedUrl(urlPolicy: OutboundUrlPolicy, field: String, url: String): URI =
        when (val check = urlPolicy.validate(url)) {
            is UrlCheck.Ok -> check.uri
            is UrlCheck.Rejected -> throw IllegalArgumentException("$PREFIX '$field' ${check.reason}")
        }

    private fun validateHeaders(config: HttpApiConfig) {
        config.defaultHeaders.keys.forEach { name ->
            require(RESERVED_HEADERS.none { it.equals(name, ignoreCase = true) }) {
                "$PREFIX 'defaultHeaders' must not set the reserved header '$name'"
            }
        }
    }

    private fun validateOperations(operations: List<OperationOverride>) {
        val seen = mutableSetOf<String>()
        operations.forEachIndexed { i, override ->
            require(seen.add(override.operationId)) {
                "$PREFIX operations[$i].operationId '${override.operationId}' is declared more than once"
            }
            override.maxResponseChars?.let {
                require(it >= HttpApiConfig.MIN_RESPONSE_CHARS) {
                    "$PREFIX operations[$i].maxResponseChars must be >= ${HttpApiConfig.MIN_RESPONSE_CHARS}, got $it"
                }
            }
        }
    }

    private fun validateBounds(config: HttpApiConfig) {
        require(config.maxTools in 1..HttpApiConfig.MAX_TOOLS_UPPER_BOUND) {
            "$PREFIX 'maxTools' must be between 1 and ${HttpApiConfig.MAX_TOOLS_UPPER_BOUND}, got ${config.maxTools}"
        }
        require(config.maxResponseChars >= HttpApiConfig.MIN_RESPONSE_CHARS) {
            "$PREFIX 'maxResponseChars' must be >= ${HttpApiConfig.MIN_RESPONSE_CHARS}, got ${config.maxResponseChars}"
        }
        require(config.timeoutSeconds >= 1) { "$PREFIX 'timeoutSeconds' must be >= 1, got ${config.timeoutSeconds}" }
        require(config.maxConcurrentCalls >= 1) {
            "$PREFIX 'maxConcurrentCalls' must be >= 1, got ${config.maxConcurrentCalls}"
        }
    }

    private const val PREFIX = "HTTP API integration config:"

    /** Headers the HTTP layer owns or that would smuggle credentials past the API key handling. */
    private val RESERVED_HEADERS = setOf("Authorization", "Proxy-Authorization", "Cookie", "Host")

    private val SPEC_FILE_EXTENSIONS = listOf(".json", ".yaml", ".yml")
}
