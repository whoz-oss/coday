package io.whozoss.agentos.selfConfig

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level policy for seeding the built-in `SELF_CONFIG` integration configuration.
 *
 * Bound from the `agentos.self-config` prefix in `application.yml`.
 * Override with environment variable: `AGENTOS_SELF_CONFIG_ENABLED_BY_DEFAULT`.
 *
 * When [enabledByDefault] is `true` (the shipped default),
 * [SelfConfigIntegrationConfigSeeder] ensures a platform-scoped
 * [io.whozoss.agentos.integrationConfig.IntegrationConfig] named `SELF_CONFIG` exists at startup,
 * with `autoGrant = false`. This makes the type visible in the catalogue so an administrator can
 * assign it to the agents that should offer self-service configuration, without having to know the
 * type string. **No agent receives the tools automatically** -- and `SELF_CONFIG` is not among the
 * auto-grantable types, so none ever can without an explicit assignment.
 *
 * This declares **existence, not content**: once the row exists it belongs to the administrator and
 * startup never rewrites it. It is also **create-only** -- setting this back to `false` later does
 * not delete an already-seeded row; a warning is logged instead.
 */
@ConfigurationProperties(prefix = "agentos.self-config")
data class SelfConfigProperties(
    val enabledByDefault: Boolean = false,
)
