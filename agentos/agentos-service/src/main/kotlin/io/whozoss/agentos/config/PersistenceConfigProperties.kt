package io.whozoss.agentos.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Supported persistence backends.
 *
 * Both modes run on a live Neo4j engine, so they share the exact same repository beans;
 * they differ only in who owns the engine. The historical `in-memory` mode was removed
 * along with the `InMemory*Repository` Spring beans and no longer exists.
 *
 * Spring Boot relaxed binding maps the kebab-case YAML / env values onto these constants:
 * `embedded-neo4j` -> [EMBEDDED_NEO4J], `neo4j` -> [NEO4J]. Any other value fails the
 * binding at startup with an explicit message, instead of silently disabling beans.
 */
enum class PersistenceMode {
    /** In-process Neo4j engine started by [EmbeddedNeo4jConfiguration]. No Docker required. */
    EMBEDDED_NEO4J,

    /** Standalone Neo4j server; the Driver comes from Spring Boot auto-configuration. */
    NEO4J,
}

/**
 * Configuration properties for persistence mode.
 *
 * Bound from the `agentos.persistence` prefix in application.yml.
 *
 * `embedded-neo4j` is the default mode: an in-process Neo4j engine starts
 * automatically, no Docker required. Use `neo4j` to connect to a standalone
 * server instead.
 *
 * Example (application.yml):
 * ```yaml
 * agentos:
 *   persistence:
 *     mode: embedded-neo4j     # or: neo4j
 * ```
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - AGENTOS_PERSISTENCE_MODE
 */
@ConfigurationProperties(prefix = "agentos.persistence")
data class PersistenceConfigProperties(
    /**
     * Root directory under which all persisted data is stored.
     * Only used when mode=embedded-neo4j. Relative paths are resolved against
     * the JVM working directory.
     */
    val dataDir: String = "data/",
    /**
     * Persistence mode:
     * - [PersistenceMode.EMBEDDED_NEO4J] (default) — in-process Neo4j engine, no Docker required
     * - [PersistenceMode.NEO4J]                    — standalone Neo4j server (configure spring.neo4j.*)
     */
    val mode: PersistenceMode = PersistenceMode.EMBEDDED_NEO4J,
    /**
     * Bolt port for the embedded Neo4j engine.
     * Defaults to 7688 to avoid conflicting with a standalone Neo4j instance
     * that typically runs on 7687. Set to 0 for a random OS-assigned port.
     * Only used when mode=embedded-neo4j.
     */
    val embeddedBoltPort: Int = 7688,

    /**
     * Host for the embedded Neo4j Bolt connector.
     * Defaults to "localhost" but can be set to "127.0.0.1" to force IPv4
     * on systems where localhost resolves to IPv6 (::1).
     * Only used when mode=embedded-neo4j.
     */
    val embeddedBoltHost: String = "localhost",

    /**
     * Transaction timeout for the embedded Neo4j engine in seconds.
     * Only used when mode=embedded-neo4j.
     */
    val embeddedTransactionTimeoutSeconds: Long = 30,
)
