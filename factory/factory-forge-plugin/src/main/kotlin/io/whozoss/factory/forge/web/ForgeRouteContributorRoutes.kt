package io.whozoss.factory.forge.web

import io.whozoss.factory.forge.port.JiraClient
import io.whozoss.factory.sdk.spi.FactoryRoute

/**
 * Builds the plugin's functional route table from the autowired plugin services.
 *
 * Kept separate from the PF4J [io.whozoss.factory.forge.plugin.ForgeRouteContributor]
 * so the route table can be exercised without a live PF4J extension.
 */
object ForgeRouteContributorRoutes {

    fun build(
        jiraClient: JiraClient,
    ): List<FactoryRoute> {
        val jira = JiraProxyController(jiraClient)
        return ForgeRoutes.routes(jira)
    }
}
