package io.whozoss.agentos.userGroup

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level policy for seeding the built-in `USER_GROUP` integration configuration.
 *
 * Bound from the `agentos.user-group` prefix in `application.yml`.
 * Override with environment variable: `AGENTOS_USER_GROUP_ENABLED_BY_DEFAULT`.
 *
 * When [enabledByDefault] is `true` (the shipped default),
 * [UserGroupIntegrationConfigSeeder] ensures a platform-scoped
 * [io.whozoss.agentos.integrationConfig.IntegrationConfig] named `USER_GROUP` exists at
 * startup with `autoGrant = false`. This makes the integration type visible in the
 * catalogue so admins can assign it to specific agents without having to know the type
 * string. No agent receives the tools automatically.
 *
 * This declares **existence, not content**: once the row exists it belongs to the
 * administrator and startup never rewrites it. It is also **create-only** — setting
 * this back to `false` later does NOT delete an already-seeded row; a warning is
 * logged instead.
 */
@ConfigurationProperties(prefix = "agentos.user-group")
data class UserGroupToolConfigProperties(
    val enabledByDefault: Boolean = true,
)
