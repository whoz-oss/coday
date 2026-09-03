package io.whozoss.agentos.agentConfig

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import io.whozoss.agentos.sdk.entity.Entity
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

/**
 * Persistent configuration of an agent within a namespace.
 *
 * An AgentConfig defines how an agent behaves: its identity (name, description),
 * its system-level instructions, and which AI model it should use.
 *
 * The [modelName] field accepts either a direct model name or an alias defined
 * by an AiProvider — resolution is deferred to the runtime layer.
 *
 * [integrations] is an optional map from integration name to an optional list of
 * allowed tool names. The map is what the agent declares, key by key: a plugin
 * integration contributes tools only when its name appears as an entry, so a null
 * map binds none of them at all. The two built-in exchange keys are the exception,
 * and they are decided per key rather than per map: an absent key defers to the
 * platform default (see the property KDoc below), whether the rest of the map is
 * set or not. A null list for a given key means all tools from that integration are
 * allowed; a non-null list restricts to exactly those tool names (or suffixes for
 * multi-instance tools named `CONFIG__tool`).
 *
 * Examples (from a Coday-style agent YAML):
 * ```yaml
 * integrations:
 *   FILES:            # all FILE_ACCESS tools
 *   JIRA:
 *     - GetIssue      # only the GetIssue tool from JIRA
 * ```
 *
 * Scoped under a Namespace via [namespaceId].
 *
 * @JsonIgnoreProperties(ignoreUnknown = true) is required because the [Entity]
 * interface exposes a computed `id` property that Jackson serialises but which
 * is not a constructor parameter.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class AgentConfig(
    override val metadata: EntityMetadata = EntityMetadata(),
    /**
     * The namespace this agent belongs to, or `null` for platform-level agents.
     */
    val namespaceId: UUID?,
    val name: String,
    val description: String? = null,
    val instructions: String? = null,
    val modelName: String? = null,
    /**
     * Integration bindings with an optional per-tool allowlist. null = no bindings:
     * [io.whozoss.agentos.tool.ToolResolverService] then resolves no tools for this agent
     * (only the platform exchange defaults described below can still grant the built-in
     * file tools).
     * Map key = integration name (matches [IntegrationConfig.name] or
     * [ToolPlugin.integrationType] for config-less plugins).
     * Map value = allowed tool names, or null for all tools of that integration.
     *
     * Reserved keys `CASE_FILE_EXCHANGE` / `NAMESPACE_FILE_EXCHANGE`
     * (see [io.whozoss.agentos.exchange.ExchangeIntegrationTypes]) enable the built-in
     * file-exchange integrations: they have no [IntegrationConfig] instance and are resolved by
     * `AgentServiceImpl.buildExchangeTools` rather than the normal plugin path. For those two keys
     * only, an **empty list is an explicit opt-out** rather than an empty allow-list, and an
     * **absent key does not mean "never granted"**: the platform defaults
     * `agentos.exchange.tools.case-enabled-by-default` /
     * `...namespace-enabled-by-default` (off by default) decide for an agent that stays silent.
     * The namespace key is further gated at run time: whatever this map says, the scope is only
     * granted when the invoking user holds Namespace READ.
     */
    val integrations: Map<String, List<String>?>? = null,
    /**
     * When true, this agent runs with the advanced multi-step orchestration loop
     * ([AgentAdvanced]) instead of the default single-call mode ([AgentSimple]).
     * Defaults to false so existing agents are unaffected.
     */
    val advancedExecution: Boolean = false,
    /**
     * Opaque metadata map for external consumers (e.g. Copilot).
     * AgentOS persists this field as-is without interpreting its content.
     * Each consumer is responsible for serializing/deserializing its own structure.
     */
    val externalMetadata: Map<String, Any?>? = null,
    /**
     * Whether this agent is published and visible to end-users.
     *
     * Defaults to `false` — newly created agents are unpublished and must be
     * explicitly published via the publish endpoint before they are accessible.
     *
     * Backward-compat: existing nodes without this field are backfilled to `false`
     * at startup by [io.whozoss.agentos.config.Neo4jSchemaInitializer].
     */
    val enabled: Boolean = false,
    /**
     * Glob patterns controlling which agents this agent is permitted to delegate to.
     *
     * When null or empty, no delegation tool is provided to the agent.
     * When non-empty, a [io.whozoss.agentos.delegation.DelegationTool] is instantiated
     * and added to the agent's tool set at build time, with the allowlist resolved by
     * matching these patterns against agents accessible to the current user in the namespace.
     *
     * `*` matches any sequence of characters (anchored, case-insensitive).
     * Examples: `["*"]` allows all agents, `["*Fixer"]` allows `BugFixer`, `StoryFixer`, etc.
     */
    val subAgents: List<String>? = null,
    /**
     * Paths to documents whose full content is injected into the agent's instructions.
     *
     * Three path patterns are supported (resolved relative to the namespace configPath):
     * - explicit file path: single file, content injected verbatim
     * - path ending with slash: directory listing (first-level only, no content)
     * - path ending with slash-star: all readable files in the directory, content injected
     *
     * Only applicable for filesystem-backed agents (namespace with a configPath).
     * Silently ignored when configPath is absent.
     */
    val docs: List<String>? = null,
    /**
     * Selectors controlling which namespace skills are advertised to this agent.
     *
     * Skills are `SKILL.md` files discovered by [io.whozoss.agentos.skill.SkillResolver] in the
     * `skills` directory of the namespace configPath, each carrying a YAML frontmatter `name` and
     * `description`. Only the catalog (name, description, path) is injected into the agent's
     * instructions; skill bodies and adjacent resources are read on demand through the agent's
     * file tools.
     *
     * Operator-visible behaviour:
     * - The `name` and `description` values injected into the prompt are whitespace-collapsed
     *   (runs of whitespace including newlines become a single space) and truncated to 120 and
     *   500 characters respectively, with an ellipsis appended when truncated. YAML folded
     *   scalars are resolved by the YAML parser before collapsing, so `description: >` still
     *   works correctly.
     * - Discovery walks at most 10 directory levels below the `skills` root and collects at most
     *   500 `SKILL.md` files per namespace; files exceeding 256 KiB are skipped with a WARN.
     * - The discovered catalog is cached per namespace for 60 seconds. Skill authors see edits
     *   reflected within a minute; steady-state agent traffic pays one filesystem walk per
     *   namespace per minute.
     *
     * Tri-state, inverse of [integrations]:
     * - null: all discovered skills are advertised (default)
     * - empty list: explicit opt-out, no skills block is produced
     * - non-empty list: union of everything the listed selectors match
     *
     * Default-on and upgrade semantics: a null `skillSelectors` means all discovered skills are
     * advertised. Because `skillSelectorsJson` is a newly added nullable Neo4j property, every
     * already-persisted agent in a namespace that has a `skills/` directory will start receiving
     * a skills block as soon as this feature is deployed — a default-on upgrade behaviour. This is
     * deliberate: it follows the Claude-compatible skill convention where discovery is opt-out
     * rather than opt-in, and the agent is expected to use the description to decide relevance.
     * This is knowingly the inverse of the exchange-tool defaults documented on
     * [io.whozoss.agentos.agent.AgentServiceImpl.buildExchangeTools], which are both off by default
     * precisely so an upgrade changes no agent's tool set. The two mechanisms make opposite choices
     * for defensible, domain-specific reasons; this comment exists so a future reader sees a
     * decision rather than an inconsistency.
     *
     * Trust boundary: injecting skill `name` and `description` verbatim into agent instructions
     * does not introduce a new privilege boundary. Anyone with write access to
     * `<configPath>/skills/` already holds write access to the agent YAML files under
     * `<configPath>/agents/`, which carry the raw `instructions` field loaded by
     * [io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository]. Authoring a skill
     * frontmatter is therefore a strictly weaker capability than what that same actor already
     * possesses: both are administrator-level namespace configuration. If `skills/` write access
     * were ever granted to a broader role than `agents/` write access, that would become a genuine
     * prompt-injection vector, so the two directories must be kept at the same privilege level.
     *
     * Selector forms, matched case-insensitively against slash-normalized paths:
     * - a lone star (`*`): every discovered skill
     * - a folder path suffixed with slash-star or slash-double-star: recursive prefix match on the
     *   path relative to the skills root; both suffixes behave identically. Example: `core`
     *   followed by slash-double-star matches `core/branch-creation`. A bare `core` carrying
     *   neither suffix is an exact match against the skill's directory path, not a prefix — it
     *   will NOT match `core/branch-creation`.
     *   (Both suffixes are spelled out in prose here rather than written literally: a slash
     *   immediately followed by a star opens a nested block comment inside KDoc, which Kotlin
     *   requires to be closed. The `docs` property KDoc above uses the same wording for the same
     *   reason.)
     * - the path relative to the skills root, with or without a trailing `SKILL.md` segment
     *   (`product/spec-writing`, `product/spec-writing/SKILL.md`)
     * - the path relative to the project root (`coday/skills/product/spec-writing/SKILL.md`)
     * - the skill's frontmatter name (`spec-writing`)
     *
     * Selectors are additive and deduplicated: overlapping selectors never duplicate a skill and the
     * result keeps discovery order (path-sorted), not selector order. A selector matching nothing is
     * logged as a warning and ignored, never an error, so a typo narrows the catalog silently rather
     * than failing agent resolution.
     *
     * Only applicable for filesystem-backed agents (namespace with a configPath).
     * Silently ignored when configPath is absent.
     */
    val skillSelectors: List<String>? = null,
) : Entity {
    /**
     * True when this [AgentConfig] was loaded from a filesystem YAML definition
     * ([io.whozoss.agentos.agentConfig.FilesystemAgentConfigRepository]) rather than persisted
     * in Neo4j.
     *
     * Filesystem agents are built in-memory on every read and never go through
     * Spring Data Neo4j's `save()`, so [EntityMetadata.version] — which SDN sets to a
     * non-null value on first persistence — stays `null` for their entire lifetime.
     * This is an explicit, named proxy for that fact: callers that need to reject
     * filesystem-only agents (e.g. before linking a [io.whozoss.agentos.prompt.Prompt] or a
     * [io.whozoss.agentos.scheduledPrompt.ScheduledPrompt]) should read this property
     * rather than re-deriving the same check from `metadata.version == null` at each
     * call site.
     */
    val isFilesystemOnly: Boolean
        get() = metadata.version == null
}
