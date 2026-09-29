package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the persistence mode.
 *
 * Bound from the `factory.persistence` prefix in `application.yml`, mirroring
 * the AgentOS `agentos.persistence` contract.
 *
 * `embedded-neo4j` is the default mode: an in-process Neo4j engine starts
 * automatically, no Docker required. Use `neo4j` to connect to a standalone
 * server instead.
 *
 * Override with environment variables (Spring Boot relaxed binding):
 * - `FACTORY_PERSISTENCE_MODE`
 */
@ConfigurationProperties(prefix = "factory.persistence")
data class PersistenceConfigProperties(
    /**
     * Root directory under which all persisted data is stored.
     * Only used when mode=embedded-neo4j. Relative paths are resolved against
     * the JVM working directory.
     */
    val dataDir: String = "build/neo4j-data",
    /**
     * Persistence mode:
     * - 'embedded-neo4j' (default) — in-process Neo4j engine, no Docker required
     * - 'neo4j'                    — standalone Neo4j server (configure spring.neo4j.*)
     */
    val mode: String = "embedded-neo4j",
    /**
     * Bolt port for the embedded Neo4j engine.
     * Defaults to 7688 to avoid conflicting with a standalone Neo4j instance
     * that typically runs on 7687. Set to 0 for a random OS-assigned port.
     * Only used when mode=embedded-neo4j.
     */
    val embeddedBoltPort: Int = 7688,
    /**
     * Host for the embedded Neo4j Bolt connector.
     * Defaults to "localhost" but can be set to "127.0.0.1" to force IPv4 on
     * systems where localhost resolves to IPv6 (::1).
     * Only used when mode=embedded-neo4j.
     */
    val embeddedBoltHost: String = "localhost",
    /**
     * Transaction timeout for the embedded Neo4j engine, in seconds.
     * Only used when mode=embedded-neo4j.
     */
    val embeddedTransactionTimeoutSeconds: Long = 30,
)
