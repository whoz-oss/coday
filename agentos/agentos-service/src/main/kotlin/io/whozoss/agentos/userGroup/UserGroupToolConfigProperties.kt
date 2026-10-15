package io.whozoss.agentos.userGroup

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level policy for the built-in `listUserGroupMembers` tool.
 *
 * Bound from the `agentos.list-user-group-members` prefix in `application.yml`.
 *
 * When [enabledByDefault] is `true` (the shipped default),
 * [UserGroupIntegrationConfigSeeder] ensures a platform-scoped
 * [io.whozoss.agentos.integrationConfig.IntegrationConfig] named
 * [UserGroupIntegrationConfigSeeder.DEFAULT_CONFIG_NAME] exists at startup,
 * with `autoGrant = true` — so every agent of the environment receives the
 * tool without declaring it.
 *
 * This declares **existence, not content**: once the configuration row exists it
 * belongs to the administrator and startup never rewrites it. It is also
 * **create-only** — setting this back to `false` later does NOT delete an
 * already-seeded configuration; a warning is logged instead.
 *
 * Override with environment variable:
 * - `AGENTOS_LIST_USER_GROUP_MEMBERS_ENABLED_BY_DEFAULT` (boolean, default `true`)
 */
@ConfigurationProperties(prefix = "agentos.user-group-tool-config")
data class UserGroupToolConfigProperties(
    val enabledByDefault: Boolean = true,
)
