package io.whozoss.factory.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Strongly-typed binding of the `factory.cockpit.*` configuration tree.
 *
 * [assetsDir] points at the vanilla cockpit directory (`factory/dashboard` by
 * default): `cockpit.html` plus the `css` and `js` asset trees are served
 * same-origin from `factory-service` so the ES-module cockpit talks to the
 * `/api/factory` endpoints and `/api/factory/workflows/stream` endpoints without a second origin (and
 * therefore without CORS). A relative value is resolved against the process
 * working directory first, then against its parent — the service is normally
 * launched from `factory-service/`.
 */
@ConfigurationProperties(prefix = "factory.cockpit")
data class CockpitProperties(
    val assetsDir: String = "factory/dashboard",
)
