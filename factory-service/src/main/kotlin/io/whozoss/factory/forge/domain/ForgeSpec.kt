package io.whozoss.factory.forge.domain

/**
 * Pure Epic-spec domain: the Epic spec frontmatter parser, its closed YAML
 * subset, structural validation and content hashing.
 *
 * Port of `factory/src/domain/forge-bmad/forge-spec.ts`. Disk access
 * ([loadForgeSpec]) lives in the infrastructure adapter.
 */
object ForgeSpec {

    /** Schema version of the Epic spec frontmatter. */
    const val FORGE_SPEC_SCHEMA_VERSION = 1

    /** Policy version of the G2 Epic gate. */
    const val G2_POLICY_VERSION = "forge-g2-deterministic-v1"

    /** The closed oracle catalog accepted by the Epic spec. */
    val ORACLE_CATALOG: Set<String> = setOf("front.build", "front.tests", "back.build")

    /** Match the frontmatter block of a Markdown spec document. */
    val FORGE_SPEC_FRONTMATTER_PATTERN = Regex("^---\\r?\\n([\\s\\S]*?)\\r?\\n---\\r?\\n")

    private val ROOT_KEY = Regex("^([A-Za-z][A-Za-z0-9]*):\\s*(.*)$")
    private val SECTION_LIST = Regex("^([A-Za-z][A-Za-z0-9]*):\\s*$")
    private val SECTION_KEY = Regex("^([A-Za-z][A-Za-z0-9]*):\\s*(.+)$")
    private val DIGITS = Regex("^\\d+$")

    internal fun fail(code: String, detail: String? = null): Nothing = throw ForgeCodedException(code, detail ?: code)

    internal fun scalar(value: String): Any? {
        val trimmed = value.trim()
        if (trimmed == "true" || trimmed == "false") return trimmed == "true"
        if (DIGITS.matches(trimmed)) return trimmed.toInt()
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))
        ) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return trimmed
    }

    /** Minimal closed YAML subset: mappings and block scalar lists only. */
    fun parseForgeSpecFrontmatter(text: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        var section: String? = null
        var list: MutableList<Any?>? = null
        for (raw in text.split("\n")) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            if (indent == 0) {
                val match = ROOT_KEY.matchEntire(line) ?: fail("G2_FRONTMATTER_INVALID")
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                if (out.containsKey(key)) fail("G2_FRONTMATTER_INVALID")
                if (value.isNotEmpty()) {
                    out[key] = scalar(value)
                    section = null
                } else {
                    out[key] = LinkedHashMap<String, Any?>()
                    section = key
                }
                list = null
                continue
            }
            if (indent == 2 && section != null && SECTION_LIST.matches(line)) {
                val newList = mutableListOf<Any?>()
                sectionMap(out, section)[line.dropLast(1)] = newList
                list = newList
                continue
            }
            if (indent == 2 && section != null) {
                val match = SECTION_KEY.matchEntire(line)
                if (match != null) {
                    sectionMap(out, section)[match.groupValues[1]] = scalar(match.groupValues[2])
                    list = null
                    continue
                }
            }
            if (indent == 2 && section != null && line.startsWith("- ") && section == "oracles") {
                val existing = out["oracles"]
                @Suppress("UNCHECKED_CAST")
                val target = (existing as? MutableList<Any?>) ?: mutableListOf<Any?>().also { out["oracles"] = it }
                target.add(scalar(line.substring(2)))
                continue
            }
            if (indent == 4 && list != null && line.startsWith("- ")) {
                list.add(scalar(line.substring(2)))
                continue
            }
            fail("G2_FRONTMATTER_INVALID")
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun sectionMap(out: MutableMap<String, Any?>, section: String): MutableMap<String, Any?> {
        val existing = out[section]
        return if (existing is MutableMap<*, *>) existing as MutableMap<String, Any?> else LinkedHashMap()
    }

    /**
     * Patterns are repo-relative POSIX paths. Exact paths and a terminal double-star
     * glob are supported; `*` matches one non-empty segment. `..`, absolute paths,
     * backslash, empty segments and glob forms other than `*` / terminal `**`
     * are rejected.
     */
    private fun validatePattern(pattern: Any?) {
        if (pattern !is String || pattern.isEmpty() ||
            pattern.contains("\\") || pattern.startsWith("/") ||
            pattern.contains("..") || pattern.contains("//")
        ) {
            fail("G2_SCOPE_PATTERN_INVALID")
        }
        val parts = pattern.split("/")
        if (parts.any { part ->
                part.isEmpty() || (part != "*" && part != "**" && !Regex("^[A-Za-z0-9._@-]+$").matches(part))
            }
        ) {
            fail("G2_SCOPE_PATTERN_INVALID")
        }
        if (parts.contains("**") && parts.last() != "**") fail("G2_SCOPE_PATTERN_INVALID")
    }

    /** Structural validation of the Epic spec frontmatter. */
    fun validateForgeSpecSchema(data: Map<String, Any?>, workItem: ForgeWorkItem) {
        if (data["schemaVersion"] != FORGE_SPEC_SCHEMA_VERSION) fail("G2_SPEC_SCHEMA_UNSUPPORTED")
        val declaredWorkItem = asMap(data["workItem"])
        if (declaredWorkItem == null ||
            declaredWorkItem["id"] != workItem.id ||
            declaredWorkItem["kind"] != workItem.kind
        ) {
            fail("G2_WORK_ITEM_MISMATCH")
        }
        val scope = asMap(data["scope"]) ?: fail("G2_SCOPE_INVALID")
        for (key in listOf("allow", "create", "deny")) {
            val entries = scope[key]
            if (entries !is List<*> || entries.isEmpty()) fail("G2_SCOPE_INVALID")
            entries.forEach { validatePattern(it) }
        }
        val oracles = data["oracles"]
        if (oracles !is List<*> || oracles.any { it !is String || it !in ORACLE_CATALOG }) {
            fail("G2_ORACLE_UNKNOWN")
        }
        if (data.keys.any { it !in setOf("schemaVersion", "workItem", "scope", "oracles") }) {
            fail("G2_FRONTMATTER_INVALID")
        }
    }

    /** Deterministic content hash of a spec document. */
    fun computeForgeSpecHash(content: String): String = ForgeJson.sha256(content)
}

const val FORGE_SPEC_SCHEMA_VERSION = ForgeSpec.FORGE_SPEC_SCHEMA_VERSION
const val G2_POLICY_VERSION = ForgeSpec.G2_POLICY_VERSION
val ORACLE_CATALOG: Set<String> = ForgeSpec.ORACLE_CATALOG

fun parseForgeSpecFrontmatter(text: String): Map<String, Any?> = ForgeSpec.parseForgeSpecFrontmatter(text)

fun computeForgeSpecHash(content: String): String = ForgeSpec.computeForgeSpecHash(content)

fun validateForgeSpecSchema(data: Map<String, Any?>, workItem: ForgeWorkItem) =
    ForgeSpec.validateForgeSpecSchema(data, workItem)
