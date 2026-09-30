package io.whozoss.factory.adapter.agentos

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.adapter.agentos.*` configuration tree.
 *
 * The adapter is **enabled by default** (`enabled=true`) since the final cutover:
 * the durable SSE bridge ([AgentOsExecutionAdapter] driven by
 * `CapabilityExecutionService`) is the primary, non-optional execution driver for
 * `agent` steps. Setting `enabled=false` explicitly demotes execution back to the
 * legacy HTTP polling turn driver (`HttpAgentOsProxyClient`) — a troubleshooting
 * fallback only. The AgentOS base URL is intentionally not duplicated here: it is
 * read from `factory.proxy.agentosUrl`.
 */
@ConfigurationProperties(prefix = "factory.adapter.agentos")
data class AgentOsAdapterProperties(
    val enabled: Boolean = true,
    /** Base delay of the SSE reconnection exponential backoff. */
    val backoffBaseMs: Long = AgentOsSseClient.DEFAULT_BACKOFF_BASE_MS,
    /** Cap of the SSE reconnection backoff. */
    val backoffMaxMs: Long = AgentOsSseClient.DEFAULT_BACKOFF_MAX_MS,
    /** Reconnection budget; exhausting it yields `Indeterminate("SSE reconnection budget exhausted")`. */
    val maxReconnects: Int = AgentOsSseClient.DEFAULT_MAX_RECONNECTS,
    /** No frame and no `:keep-alive` within this window ⇒ the stream is treated as dropped. */
    val stallTimeoutMs: Long = AgentOsSseClient.DEFAULT_STALL_TIMEOUT_MS,
    /** Default wall-clock observation budget for a turn. */
    val observationTimeoutMs: Long = 600_000L,
)
