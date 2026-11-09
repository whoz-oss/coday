package io.whozoss.factory.forge.plugin

import io.whozoss.factory.forge.port.JiraClient
import io.whozoss.factory.forge.web.ForgeRouteContributorRoutes
import io.whozoss.factory.sdk.spi.FactoryRoute
import io.whozoss.factory.sdk.spi.FactoryRouteContributor
import org.pf4j.Extension

/**
 * PF4J extension exposing the plugin's `/api/jira/...` routes.
 *
 * The host discovers this class through `META-INF/extensions.idx` and mounts the
 * routes via its dynamic router.
 */
@Extension
class ForgeRouteContributor(
    jiraClient: JiraClient,
) : FactoryRouteContributor {

    private val routes: List<FactoryRoute> = ForgeRouteContributorRoutes.build(
        jiraClient = jiraClient,
    )

    override fun getRoutes(): List<FactoryRoute> = routes
}
