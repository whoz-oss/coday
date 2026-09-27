package io.whozoss.factory.forge.domain

/**
 * Pure Story-spec domain: the Story spec frontmatter parser, structural
 * validation and the deterministic Epic/Story inheritance rules used by G2-US.
 *
 * Port of `factory/src/domain/forge-bmad/forge-story-spec.ts`. Disk access
 * (`readStorySpec`, `hashStorySpec`) lives in the infrastructure adapter.
 */
object ForgeStorySpec {

    /** Schema version of the Story spec frontmatter. */
    const val FORGE_STORY_SPEC_SCHEMA_VERSION = 1

    /** Policy version of the G2-US Story gate. */
    const val G2_US_POLICY_VERSION = "forge-g2-us-deterministic-v1"

    private val ALLOWED_KEYS = setOf(
        "schemaVersion",
        "workItem",
        "scope",
        "oracles",
        "acceptanceCriteria",
        "impacts",
    )

    private val ROOT_KEY = Regex("^([A-Za-z][A-Za-z0-9]*):\\s*(.*)$")
    private val SECTION_LIST = Regex("^([A-Za-z][A-Za-z0-9]*):\\s*$")
    private val SECTION_KEY = Regex("^([A-Za-z][A-Za-z0-9]*):\\s*(.+)$")

    /**
     * Minimal closed YAML subset — same parser as forge-spec, extended for
     * acceptanceCriteria and impacts (list of strings under root).
     */
    fun parseStorySpecFrontmatter(text: String): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        var section: String? = null
        var list: MutableList<Any?>? = null
        for (raw in text.split("\n")) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            if (indent == 0) {
                val match = ROOT_KEY.matchEntire(line) ?: ForgeSpec.fail("G2_FRONTMATTER_INVALID")
                val key = match.groupValues[1]
                val value = match.groupValues[2]
                if (out.containsKey(key)) ForgeSpec.fail("G2_FRONTMATTER_INVALID")
                if (value.isNotEmpty()) {
                    out[key] = ForgeSpec.scalar(value)
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
                    sectionMap(out, section)[match.groupValues[1]] = ForgeSpec.scalar(match.groupValues[2])
                    list = null
                    continue
                }
            }
            if (indent == 2 && section != null && line.startsWith("- ")) {
                val existing = out[section]
                @Suppress("UNCHECKED_CAST")
                val target = (existing as? MutableList<Any?>) ?: mutableListOf<Any?>().also { out[section] = it }
                target.add(ForgeSpec.scalar(line.substring(2)))
                continue
            }
            if (indent == 4 && list != null && line.startsWith("- ")) {
                list.add(ForgeSpec.scalar(line.substring(2)))
                continue
            }
            ForgeSpec.fail("G2_FRONTMATTER_INVALID")
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun sectionMap(out: MutableMap<String, Any?>, section: String): MutableMap<String, Any?> {
        val existing = out[section]
        return if (existing is MutableMap<*, *>) existing as MutableMap<String, Any?> else LinkedHashMap()
    }

    /** Validate structural requirements of a Story spec frontmatter. */
    fun validateStorySpec(data: Map<String, Any?>) {
        if (data["schemaVersion"] != FORGE_STORY_SPEC_SCHEMA_VERSION) ForgeSpec.fail("G2_US_SPEC_SCHEMA_UNSUPPORTED")
        for (key in data.keys) {
            if (key !in ALLOWED_KEYS) ForgeSpec.fail("G2_FRONTMATTER_INVALID", "unexpected key: $key")
        }
        val workItem = asMap(data["workItem"]) ?: ForgeSpec.fail("G2_US_WORK_ITEM_KIND_INVALID")
        if (workItem["kind"] != "Story") ForgeSpec.fail("G2_US_WORK_ITEM_KIND_INVALID")
        if ((workItem["id"] as? String).isNullOrEmpty()) ForgeSpec.fail("G2_FRONTMATTER_INVALID")
        if ((workItem["parentId"] as? String).isNullOrEmpty()) ForgeSpec.fail("G2_US_PARENT_ID_MISSING")
        val scope = asMap(data["scope"]) ?: ForgeSpec.fail("G2_SCOPE_INVALID")
        for (key in listOf("allow", "create", "deny")) {
            val entries = scope[key]
            if (entries !is List<*> || entries.isEmpty()) ForgeSpec.fail("G2_SCOPE_INVALID")
        }
        val oracles = data["oracles"]
        if (oracles != null && oracles !is List<*>) ForgeSpec.fail("G2_FRONTMATTER_INVALID")
        for (key in listOf("acceptanceCriteria", "impacts")) {
            val value = data[key]
            if (value != null && value !is List<*>) ForgeSpec.fail("G2_FRONTMATTER_INVALID")
        }
    }

    /** A single inheritance violation reported by G2-US. */
    data class InheritanceViolation(val code: String, val detail: String)

    data class InheritanceResult(val valid: Boolean, val violations: List<InheritanceViolation>)

    /**
     * Validate inheritance rules between a Story spec and its Epic spec.
     *
     * Rules: allow/create (Story) ⊆ (Epic); deny (Story) ⊇ (Epic); oracles
     * (Story) ⊆ (Epic). Inheritance is validated by exact string set membership,
     * not glob resolution (deliberately conservative).
     */
    fun validateInheritance(storySpec: Map<String, Any?>, epicSpec: Map<String, Any?>): InheritanceResult {
        val violations = mutableListOf<InheritanceViolation>()
        val storyScope = asMap(storySpec["scope"]) ?: emptyMap()
        val epicScope = asMap(epicSpec["scope"]) ?: emptyMap()

        val epicAllow = (epicScope["allow"] as? List<*>).orEmpty().map { it.toString() }.toSet()
        val epicCreate = (epicScope["create"] as? List<*>).orEmpty().map { it.toString() }.toSet()
        val epicDeny = (epicScope["deny"] as? List<*>).orEmpty().map { it.toString() }.toSet()
        val epicOracles = (epicSpec["oracles"] as? List<*>).orEmpty().map { it.toString() }.toSet()

        for (pattern in (storyScope["allow"] as? List<*>).orEmpty().map { it.toString() }) {
            if (pattern !in epicAllow) {
                violations.add(InheritanceViolation("G2_US_ALLOW_EXCEEDS_EPIC", "allow pattern \"$pattern\" not in Epic allow set"))
            }
        }
        for (pattern in (storyScope["create"] as? List<*>).orEmpty().map { it.toString() }) {
            if (pattern !in epicCreate) {
                violations.add(
                    InheritanceViolation("G2_US_CREATE_EXCEEDS_EPIC", "create pattern \"$pattern\" not in Epic create set"),
                )
            }
        }
        val storyDeny = (storyScope["deny"] as? List<*>).orEmpty().map { it.toString() }.toSet()
        for (pattern in epicDeny) {
            if (pattern !in storyDeny) {
                violations.add(
                    InheritanceViolation("G2_US_DENY_WEAKER_THAN_EPIC", "Epic deny pattern \"$pattern\" missing from Story deny set"),
                )
            }
        }
        for (oracle in (storySpec["oracles"] as? List<*>).orEmpty().map { it.toString() }) {
            if (oracle !in epicOracles) {
                violations.add(
                    InheritanceViolation("G2_US_ORACLE_UNKNOWN_IN_EPIC", "oracle \"$oracle\" not declared in Epic oracles"),
                )
            }
        }
        return InheritanceResult(violations.isEmpty(), violations)
    }

    /** Deterministic content hash of a Story spec document. */
    fun computeStorySpecHash(content: String): String = ForgeJson.sha256(content)
}

const val FORGE_STORY_SPEC_SCHEMA_VERSION = ForgeStorySpec.FORGE_STORY_SPEC_SCHEMA_VERSION
const val G2_US_POLICY_VERSION = ForgeStorySpec.G2_US_POLICY_VERSION

fun parseStorySpecFrontmatter(text: String): Map<String, Any?> = ForgeStorySpec.parseStorySpecFrontmatter(text)

fun validateStorySpec(data: Map<String, Any?>) = ForgeStorySpec.validateStorySpec(data)

fun computeStorySpecHash(content: String): String = ForgeStorySpec.computeStorySpecHash(content)
