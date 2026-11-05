package io.whozoss.agentos.effectiveConfig

/**
 * One setting the administration page reports on.
 *
 * The registry below is an allow-list on purpose: the page never dumps `System.getenv()` or the whole Spring
 * `Environment`, which hold secrets. A setting that is not listed here is not shown.
 *
 * @property propertyKey Spring property (`agentos.git.workspaces.enabled`), or null for a variable only read as
 *   an environment variable (a plugin reading `System.getenv`).
 * @property envVar Name of the OS environment variable that overrides it, or null when there is none.
 * @property sensitive Forces masking. [isSensitiveName] masks on the name too, so a secret added here and
 *   named like one is protected twice.
 */
internal data class KnownSetting(
    val propertyKey: String?,
    val envVar: String?,
    val description: String,
    val sensitive: Boolean = false,
)

/** Spring Boot relaxed binding: `agentos.git.workspaces-enabled` is overridden by `AGENTOS_GIT_WORKSPACES_ENABLED`. */
internal fun relaxedEnvName(propertyKey: String): String = propertyKey.uppercase().replace('.', '_').replace('-', '_')

private val SENSITIVE_FRAGMENTS = listOf("key", "secret", "token", "password", "credential", "salt")

/**
 * Safety net over the registry: a name that looks like a secret is masked, whatever the registry says.
 * It errs on the masking side (`contains`, separators ignored), a false positive only hides a harmless value.
 */
internal fun isSensitiveName(name: String): Boolean {
    val normalized = name.lowercase().filter { it.isLetterOrDigit() }
    return SENSITIVE_FRAGMENTS.any { it in normalized }
}

private fun property(
    key: String,
    description: String,
    env: String? = relaxedEnvName(key),
    sensitive: Boolean = false,
) = KnownSetting(key, env, description, sensitive)

private fun envOnly(
    name: String,
    description: String,
) = KnownSetting(null, name, description)

internal val KNOWN_SETTINGS: List<KnownSetting> =
    listOf(
        // --- Platform ---
        property("agentos.security.mode", "Identity resolution: local (OS user) or auth (proxy headers)"),
        property("agentos.bootstrap.enabled", "Create the first super-admin on an empty database"),
        property("agentos.bootstrap.admin-external-id", "External id of the bootstrap super-admin"),
        property("agentos.persistence.mode", "Persistence: embedded-neo4j or neo4j"),
        property("agentos.persistence.data-dir", "Directory of the embedded database"),
        property("agentos.plugins.dir", "Directory the plugins are loaded from"),
        property("agentos.oauth.redirect-uri", "OAuth redirect URI; blank disables interactive OAuth flows"),
        property("agentos.oauth.flow-timeout-minutes", "How long an interactive OAuth flow waits for the user"),
        property("agentos.encryption.key", "Key encrypting stored secrets", sensitive = true),
        property("agentos.encryption.salt", "Salt of the secret encryption", sensitive = true),
        property("spring.servlet.multipart.max-file-size", "Largest uploaded file", env = "AGENTOS_MULTIPART_MAX_FILE_SIZE"),
        property("spring.servlet.multipart.max-request-size", "Largest upload request", env = "AGENTOS_MULTIPART_MAX_REQUEST_SIZE"),
        property("spring.ai.mcp.client.enabled", "MCP client auto-configuration", env = "MCP_ENABLED"),
        property("spring.ai.openai.api-key", "OpenAI key of the Spring AI auto-configuration", env = "OPENAI_API_KEY", sensitive = true),
        property("spring.ai.anthropic.api-key", "Anthropic key of the Spring AI auto-configuration", env = "ANTHROPIC_API_KEY", sensitive = true),
        // --- Features ---
        property("agentos.usage.enabled", "LLM usage accounting and the run-cost guard"),
        property("agentos.anthropic.prompt-caching-enabled", "Anthropic prompt caching"),
        property("agentos.personal-namespace.enabled", "A private namespace for every user"),
        property("agentos.defaults.coday-agent.enabled", "A 'Coday' agent in every personal namespace"),
        property("agentos.query-user.enabled-by-default", "Seed the auto-granted QUERY_USER integration"),
        property("agentos.user-group.enabled-by-default", "Seed the USER_GROUP integration"),
        property("agentos.case-plugin.enabled-by-default", "Seed the CASE integration"),
        property("agentos.integrations.user-scope-denied-types", "Integration types refused in user scopes"),
        property("agentos.integrations.auto-grantable-types", "Integration types that may be auto-granted"),
        // --- Git ---
        property("agentos.git.workspaces.enabled", "Git worktrees for case families"),
        property("agentos.git.worker.enabled", "Background Git worker"),
        property("agentos.git.binary", "Git executable"),
        property("agentos.git.default-timeout", "Timeout of ordinary Git commands"),
        property("agentos.git.clone-timeout", "Timeout of clone, fetch and worktree operations"),
        property("agentos.git.setup-timeout", "Timeout of the worktree setup command"),
        property("agentos.git.max-output-chars", "Cap on captured Git output"),
        property("agentos.git.allowed-remote-protocols", "Transports Git may use"),
        property("agentos.git.allow-private-remote-hosts", "Allow loopback and private-network remotes"),
        // --- Files exchange ---
        property("agentos.exchange.mount-root", "Root directory of the file exchange"),
        property("agentos.exchange.allowed-upload-extensions", "Extensions accepted for uploads; empty accepts any"),
        property("agentos.exchange.read-max-size-bytes", "Largest file the read endpoints load"),
        property("agentos.exchange.tools.case-enabled-by-default", "Case file tools granted to every agent"),
        property("agentos.exchange.tools.namespace-enabled-by-default", "Namespace file tools granted to every agent"),
        property("agentos.exchange.tools.extra-deny-patterns", "Extra file names denied to the file tools"),
        property("agentos.exchange.tools.image-max-dimension", "Longest edge of images handed to the LLM"),
        property("agentos.exchange.tools.image-jpeg-quality", "JPEG quality of those images"),
        property("agentos.exchange.tools.image-max-source-pixels", "Largest source image accepted"),
        property("agentos.exchange.tools.image-pass-through-max-bytes", "Size under which images are not re-encoded"),
        property("agentos.exchange.tools.document-max-output-chars", "Markdown budget of one readDocument call"),
        property("agentos.exchange.tools.document-max-attached-images", "Pictures readDocument attaches per call"),
        property("agentos.exchange.tools.document-max-table-columns", "Table columns kept by readDocument"),
        property("agentos.exchange.tools.document-max-cell-chars", "Table cell length kept by readDocument"),
        // --- Limits and defaults ---
        property("agentos.limits.case-max-iterations", "Internal steps per user message before a case errors"),
        property("agentos.limits.agent-max-iterations", "Tool rounds per agent run"),
        property("agentos.limits.agent-loop-max-items", "Entities processed by one agent loop"),
        property("agentos.limits.run-cost-threshold", "Spending accepted without confirmation; empty means no limit"),
        property("agentos.defaults.delegation-timeout-seconds", "Inactivity budget of a delegation"),
        property("agentos.defaults.agent-name", "Fallback agent of a namespace without a default"),
        property("agentos.defaults.image-char-cost", "Context cost of one tool-response image"),
        property("agentos.defaults.max-attached-images", "Tool-response images attached to a prompt"),
        property("agentos.case.idle-eviction-grace-ms", "Delay before an idle case runtime is evicted"),
        property("agentos.case.sse-heartbeat-interval-ms", "Interval of the SSE keep-alive frames"),
        // --- Prompts and scheduler ---
        property("agentos.prompt.batch-translation.parallelism-limit", "Concurrent prompt translations"),
        property("agentos.prompt.translation.cache.max-size", "Entries of the translation cache"),
        property("agentos.prompt.translation.cache.ttl", "Lifetime of a cached translation"),
        property("agentos.prompt.scheduler.enabled", "Scheduled prompt engine"),
        property("agentos.prompt.scheduler.tick-interval-ms", "Interval between scheduler ticks"),
        property("agentos.prompt.scheduler.batch-size", "Runs claimed per producer iteration"),
        property("agentos.prompt.scheduler.worker-count", "Parallel scheduler workers"),
        property("agentos.prompt.scheduler.channel-capacity", "Buffer between producer and workers"),
        property("agentos.prompt.scheduler.empty-poll-delay-ms", "Delay when nothing is due"),
        property("agentos.prompt.scheduler.paused-poll-delay-ms", "Delay between iterations while paused"),
        property("agentos.prompt.scheduler.launch-timeout-seconds", "Wait for a launched case to settle"),
        property("agentos.prompt.scheduler.lease-minutes", "Lease of a running scheduled run"),
        property("agentos.prompt.scheduler.windows", "UTC execution windows; empty runs continuously"),
        // --- Plugins reading the environment directly ---
        envOnly("AGENTOS_HTTP_API_DISALLOW_UNSECURE", "HTTP_API plugin: true restricts it to public HTTPS"),
    )
