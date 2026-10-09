package io.whozoss.factory.oracle.domain

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest

/**
 * Strict validation, canonical serialization and content hashing of oracle
 * definitions.
 *
 * Faithful port of `validateOracleDefinition` / `canonicalOracleDefinition` /
 * `hashOracleDefinition` in `factory/src/domain/oracle/oracle-definition.ts`.
 * Any violation is reported as an [InvalidOracleDefinitionException] carrying the
 * machine code `INVALID_ORACLE_DEFINITION`.
 *
 * Pure: no filesystem, no HTTP, no clock — the registry owns I/O.
 */
object OracleDefinitionValidator {

    private val SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val VERSION = Regex("^\\d+\\.\\d+\\.\\d+$")
    private val FORBIDDEN_ARG_CHARS = Regex("[\\r\\n\\u0000]")

    private val ROOT_FIELDS = setOf(
        "schemaVersion",
        "id",
        "version",
        "domain",
        "argv",
        "cwd",
        "timeoutMs",
        "success",
        "applicable",
    )
    private val SUCCESS_FIELDS = setOf("rule", "requireWork")
    private val APPLICABLE_FIELDS = setOf("workflowTypes", "stepIds")

    /** Interpreters embedding a second command language in a definition. */
    private val SHELL_EXECUTABLES = setOf(
        "sh",
        "bash",
        "zsh",
        "dash",
        "ksh",
        "cmd",
        "cmd.exe",
        "powershell",
        "powershell.exe",
        "pwsh",
        "pwsh.exe",
    )

    /** Shell-evaluation flags that would turn `argv` into a shell command. */
    private val SHELL_FLAGS = setOf("-c", "--command", "/c", "-command", "-encodedcommand")

    private const val MAX_ARGV = 32
    private const val MAX_ARG_LENGTH = 512
    private const val MIN_TIMEOUT_MS = 1L
    private const val MAX_TIMEOUT_MS = 3_600_000L

    /** Validate a raw JSON document and return the strongly-typed definition. */
    fun validate(json: String, mapper: ObjectMapper): OracleDefinition {
        val node = try {
            mapper.readTree(json)
        } catch (exception: Exception) {
            invalid("Oracle definition is not valid JSON", exception)
        }
        return validate(node, mapper)
    }

    /** Validate a raw map (convenience for tests / in-memory definitions). */
    fun validate(value: Map<String, Any?>, mapper: ObjectMapper): OracleDefinition =
        validate(mapper.valueToTree<JsonNode>(value), mapper)

    /** Validate a parsed JSON tree and return the strongly-typed definition. */
    fun validate(node: JsonNode, mapper: ObjectMapper): OracleDefinition {
        if (node.isNull || !node.isObject) invalid("Oracle definition must be a JSON object")
        val root = node as ObjectNode
        if (root.fieldNames().asSequence().any { it !in ROOT_FIELDS }) {
            invalid("Oracle definition contains unknown fields")
        }

        val schemaVersion = requiredText(root, "schemaVersion")
        val id = requiredText(root, "id")
        val version = requiredText(root, "version")
        val domain = requiredText(root, "domain")
        if (schemaVersion != OracleDefinition.SCHEMA_VERSION) {
            invalid("Unsupported oracle schema version '$schemaVersion'")
        }
        if (!SAFE.matches(id)) invalid("Invalid oracle id '$id'")
        if (!VERSION.matches(version)) invalid("Invalid oracle version '$version'")
        if (!SAFE.matches(domain)) invalid("Invalid oracle domain '$domain'")

        val argv = validateArgv(root.get("argv"))
        val cwd = requiredText(root, "cwd")
        if (cwd != OracleDefinition.REPO_ROOT) invalid("Oracle cwd must be '${OracleDefinition.REPO_ROOT}'")
        val timeoutMs = validateTimeout(root.get("timeoutMs"))
        val success = validateSuccess(root.get("success"))
        val applicable = validateApplicable(root.get("applicable"))

        return OracleDefinition(
            schemaVersion = schemaVersion,
            id = id,
            version = version,
            domain = domain,
            argv = argv,
            cwd = cwd,
            timeoutMs = timeoutMs,
            success = success,
            applicable = applicable,
        )
    }

    private fun validateArgv(node: JsonNode?): List<String> {
        if (node == null || !node.isArray || node.isEmpty || node.size() > MAX_ARGV) {
            invalid("Oracle argv must be an array of 1 to $MAX_ARGV arguments")
        }
        val argv = ArrayList<String>(node.size())
        for (entry in node) {
            if (!entry.isTextual) invalid("Every oracle argument must be a string")
            val value = entry.asText()
            if (value.isEmpty() || value.length > MAX_ARG_LENGTH || FORBIDDEN_ARG_CHARS.containsMatchIn(value)) {
                invalid("Invalid oracle argument")
            }
            argv.add(value)
        }
        // `argv` executes directly without a shell; reject shell interpreters and
        // shell-evaluation flags anyway so a definition stays a fixed contract.
        val executable = argv.first().replace('\\', '/').substringAfterLast('/').lowercase()
        if (executable.isEmpty() || executable in SHELL_EXECUTABLES) {
            invalid("Oracle argv must not invoke a shell interpreter")
        }
        if (argv.drop(1).any { it.lowercase() in SHELL_FLAGS }) {
            invalid("Oracle argv must not carry shell-evaluation flags")
        }
        return argv
    }

    private fun validateTimeout(node: JsonNode?): Long {
        if (node == null || !node.isIntegralNumber || !node.canConvertToLong()) {
            invalid("Oracle timeoutMs must be an integer")
        }
        val timeoutMs = node.asLong()
        if (timeoutMs < MIN_TIMEOUT_MS || timeoutMs > MAX_TIMEOUT_MS) {
            invalid("Oracle timeoutMs must be between $MIN_TIMEOUT_MS and $MAX_TIMEOUT_MS")
        }
        return timeoutMs
    }

    private fun validateSuccess(node: JsonNode?): OracleSuccessCondition {
        if (node == null || !node.isObject) invalid("Oracle success condition is required")
        if (node.fieldNames().asSequence().any { it !in SUCCESS_FIELDS }) {
            invalid("Oracle success condition contains unknown fields")
        }
        if (requiredText(node, "rule") != OracleSuccessCondition.RULE) {
            invalid("Oracle success rule must be '${OracleSuccessCondition.RULE}'")
        }
        val requireWork = node.get("requireWork")
        if (requireWork == null || !requireWork.isBoolean) {
            invalid("Oracle success.requireWork must be a boolean")
        }
        return OracleSuccessCondition(rule = OracleSuccessCondition.RULE, requireWork = requireWork.asBoolean())
    }

    private fun validateApplicable(node: JsonNode?): OracleApplicableCondition {
        if (node == null || !node.isObject) invalid("Oracle applicable condition is required")
        if (node.fieldNames().asSequence().any { it !in APPLICABLE_FIELDS }) {
            invalid("Oracle applicable condition contains unknown fields")
        }
        return OracleApplicableCondition(
            workflowTypes = validateSafeList(node.get("workflowTypes"), "workflowTypes"),
            stepIds = validateSafeList(node.get("stepIds"), "stepIds"),
        )
    }

    private fun validateSafeList(node: JsonNode?, field: String): List<String> {
        if (node == null || !node.isArray || node.isEmpty) {
            invalid("Oracle applicable.$field must be a non-empty array")
        }
        val values = ArrayList<String>(node.size())
        for (entry in node) {
            if (!entry.isTextual) invalid("Oracle applicable.$field entries must be strings")
            val value = entry.asText()
            if (!SAFE.matches(value)) invalid("Invalid oracle applicable.$field entry '$value'")
            values.add(value)
        }
        return values
    }

    private fun requiredText(node: JsonNode, field: String): String {
        val value = node.get(field) ?: invalid("Oracle definition is missing '$field'")
        if (!value.isTextual) invalid("Oracle definition field '$field' must be a string")
        return value.asText()
    }

    /**
     * Canonical JSON: object keys sorted recursively, arrays preserved in order.
     * Two definitions that differ only by key order serialize identically.
     */
    fun canonicalJson(definition: OracleDefinition, mapper: ObjectMapper): String =
        mapper.writeValueAsString(canonicalize(mapper.valueToTree(definition)))

    /** A stable `sha256:<hex>` content hash of a definition, insensitive to key order. */
    fun hash(definition: OracleDefinition, mapper: ObjectMapper): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(definition, mapper).toByteArray(Charsets.UTF_8))
        return "sha256:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** The `id@version` identity encoded in a definition file name (extension stripped). */
    fun identityFromFileName(fileName: String): String {
        val base = fileName.replace('\\', '/').substringAfterLast('/')
        return if (base.endsWith(".json")) base.dropLast(".json".length) else base
    }

    private fun canonicalize(node: JsonNode): JsonNode {
        if (node.isObject) {
            val result = JsonNodeFactory.instance.objectNode()
            node.fieldNames().asSequence().sorted().forEach { name ->
                result.set<JsonNode>(name, canonicalize(node.get(name)))
            }
            return result
        }
        if (node.isArray) {
            val result = JsonNodeFactory.instance.arrayNode()
            node.forEach { entry -> result.add(canonicalize(entry)) }
            return result
        }
        return node
    }

    private fun invalid(message: String, cause: Throwable? = null): Nothing =
        throw InvalidOracleDefinitionException(message, null, cause)
}
