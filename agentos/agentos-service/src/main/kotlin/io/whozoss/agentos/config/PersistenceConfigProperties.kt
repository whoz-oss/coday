package io.whozoss.agentos.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for persistence mode.
 *
 * Bound from the `agentos.persistence` prefix in application.yml.
 *
 * File-system persistence is the **default** mode.
 * Set `agentos.persistence.mode=in-memory` to switch to in-memory repositories
 * (useful for tests or lightweight local runs where data loss on restart is acceptable).
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   persistence:
 *     data-dir: data/          # root directory for persisted files (default)
 *     mode: filesystem         # or: in-memory
 * ```
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_PERSISTENCE_DATA_DIR
 * - AGENTOS_PERSISTENCE_MODE
 */
@ConfigurationProperties(prefix = "agentos.persistence")
data class PersistenceConfigProperties(
    /**
     * Root directory under which all persisted data is stored.
     * Relative paths are resolved against the JVM working directory.
     */
    val dataDir: String = "data/",
    /**
     * Persistence mode: 'filesystem' (default) or 'in-memory'.
     */
    val mode: String = "filesystem",
    /**
     * Bolt port for the embedded Neo4j engine.
     * Defaults to 7688 to avoid conflicting with a standalone Neo4j instance
     * that typically runs on 7687. Set to 0 for a random OS-assigned port.
     * Only used when mode=embedded-neo4j.
     */
    val embeddedBoltPort: Int = 7688,
)
