package io.whozoss.factory.sdk.spi

import org.pf4j.ExtensionPoint

/**
 * Extension point allowing a plugin to contribute HTTP routes to the host.
 *
 * The default contributes nothing, so a plugin only overrides [getRoutes] when
 * it actually wants to expose endpoints. Routes are mounted dynamically by the
 * host; the SDK stays free of any web-framework dependency.
 */
interface FactoryRouteContributor : ExtensionPoint {
    fun getRoutes(): List<FactoryRoute> = emptyList()
}
