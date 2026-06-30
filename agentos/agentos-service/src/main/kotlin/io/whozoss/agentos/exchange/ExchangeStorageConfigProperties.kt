package io.whozoss.agentos.exchange

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the AgentOS file exchange.
 *
 * Bound from the `agentos.exchange` prefix in application.yml.
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   exchange:
 *     mount-root: data/exchange/
 * ```
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_EXCHANGE_MOUNT_ROOT
 */
@ConfigurationProperties(prefix = "agentos.exchange")
data class ExchangeStorageConfigProperties(
    /**
     * Root directory under which all exchange files are stored.
     * Relative paths are resolved against the JVM working directory.
     *
     * Per-scope layout below this root:
     * - `<mountRoot>/<namespaceId>/cases/<YYYY>/<MM>/<DD>/<caseId>` — case-scoped files (date-sharded)
     * - `<mountRoot>/<namespaceId>/shared`         — namespace-shared files
     */
    val mountRoot: String = "data/exchange/",
)
