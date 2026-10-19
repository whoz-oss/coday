package io.whozoss.agentos.casePlugin

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Platform-level policy for the built-in `CASE` integration ([CaseToolPlugin]: `ReadCase` and
 * `FreeCaseLauncher`).
 *
 * Bound from the `agentos.case-plugin` prefix in `application.yml`. A prefix of its own, because
 * `agentos.case` is already bound to [io.whozoss.agentos.caseFlow.CaseConfigProperties].
 *
 * This property is purely declarative: it states **whether a platform-scoped `CASE` configuration
 * should exist**, and [CaseIntegrationConfigSeeder] reconciles that statement at startup. It says
 * nothing about how the tools are granted, nor about what the configuration contains once it exists.
 *
 * Override with environment variable:
 * - `AGENTOS_CASE_PLUGIN_ENABLED_BY_DEFAULT` (boolean, default `false`; shipped as `true`)
 */
@ConfigurationProperties(prefix = "agentos.case-plugin")
data class CasePluginConfigProperties(
    /**
     * When `true`, [CaseIntegrationConfigSeeder] ensures a platform-scoped
     * [io.whozoss.agentos.integrationConfig.IntegrationConfig] named
     * [CaseIntegrationConfigSeeder.DEFAULT_CONFIG_NAME] exists at startup, so that an agent only has to
     * name it in its `integrations` to receive `ReadCase` and `FreeCaseLauncher`.
     *
     * The seeded configuration is **not** auto-granted: `CASE` is not an auto-grantable type
     * ([io.whozoss.agentos.integrationConfig.IntegrationsProperties.autoGrantableTypes]), and
     * should not become one, since `FreeCaseLauncher` starts work. Each agent opts in explicitly.
     *
     * When `false` (the code default), nothing is seeded. An administrator creates the configuration
     * explicitly, at whatever scope suits.
     *
     * ## Two properties of the seeder this flag does NOT have
     *
     * It declares **existence, not content**. Once the configuration exists it belongs to the
     * administrator: startup never rewrites it, however far it has drifted.
     *
     * It is **create-only, never delete**. Flipping this back to `false` on an environment that was
     * already seeded leaves the configuration in place. The seeder logs a warning naming the remedy
     * rather than silently deleting data on the strength of a flag.
     */
    val enabledByDefault: Boolean = false,
)
