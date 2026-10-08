package io.whozoss.agentos.queryUser

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level policy for the built-in `queryUser` tool.
 *
 * Bound from the `agentos.query-user` prefix in `application.yml`.
 *
 * ## What this property does -- and no longer does
 *
 * It used to drive the grant decision itself, through a dedicated service that bypassed
 * [io.whozoss.agentos.tool.ToolResolverService] entirely. That parallel path is gone:
 * `QUERY_USER` is now an ordinary declared integration like any other, and the single
 * mechanism that hands it to agents is
 * [io.whozoss.agentos.integrationConfig.IntegrationConfig.autoGrant].
 *
 * What remains is narrower and purely declarative: this property states **whether a
 * platform-scoped `QUERY_USER` configuration should exist**, and
 * [QueryUserConfigSeeder] reconciles that statement at startup. It says nothing about how
 * the tool is granted, and nothing about what the configuration contains once it exists.
 *
 * Override with environment variable:
 * - `AGENTOS_QUERY_USER_ENABLED_BY_DEFAULT` (boolean, default `false`; shipped as `true`)
 */
@ConfigurationProperties(prefix = "agentos.query-user")
data class QueryUserConfigProperties(
    /**
     * When `true`, [QueryUserConfigSeeder] ensures a platform-scoped
     * [io.whozoss.agentos.integrationConfig.IntegrationConfig] named
     * [QueryUserConfigSeeder.DEFAULT_CONFIG_NAME] exists at startup, with `autoGrant = true`
     * and all three question types allowed. Every agent of the environment then receives the
     * tool without declaring it -- reproducing the historical behaviour.
     *
     * When `false` (the code default), nothing is seeded. An administrator who still wants
     * the tool creates the configuration explicitly, at whatever scope suits.
     *
     * ## Two properties of the seeder this flag does NOT have
     *
     * It declares **existence, not content**. Once the configuration exists it belongs to the
     * administrator: startup never rewrites it, however far it has drifted.
     *
     * It is **create-only, never delete**. Flipping this back to `false` on an environment
     * that was already seeded leaves the configuration in place, still auto-granting. The
     * seeder logs a warning naming the remedy rather than silently deleting data on the
     * strength of a flag.
     */
    val enabledByDefault: Boolean = false,
)
