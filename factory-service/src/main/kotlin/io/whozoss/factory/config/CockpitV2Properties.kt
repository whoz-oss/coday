package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.cockpit.v2.*` configuration tree.
 *
 * [assetsDir] points at the Angular build output of `apps/cockpit-v2`
 * (`apps/cockpit-v2/dist/browser` by default): `index.html` plus the hashed
 * `*.js` / `*.css` asset trees are served same-origin from `factory-service` so
 * the Cockpit V2 SPA can reach the Factory and AgentOS endpoints without a
 * second origin (and therefore without CORS). A relative value is resolved
 * against the process working directory first, then against its parent — the
 * service is normally launched from `factory-service/`.
 *
 * This is deliberately additive: the legacy `/cockpit` surface keeps its own
 * [CockpitProperties] (`factory.cockpit.assets-dir`) and is left untouched.
 */
@ConfigurationProperties(prefix = "factory.cockpit.v2")
data class CockpitV2Properties(
    val assetsDir: String = "apps/cockpit-v2/dist/browser",
)
