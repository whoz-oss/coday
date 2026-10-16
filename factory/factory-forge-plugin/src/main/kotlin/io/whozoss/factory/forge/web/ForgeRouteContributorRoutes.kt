package io.whozoss.factory.forge.web

import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.forge.port.JiraClient
import io.whozoss.factory.forge.service.ForgeGateService
import io.whozoss.factory.forge.service.ForgeRunService
import io.whozoss.factory.forge.service.StoryOperationService
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.sdk.spi.FactoryRoute

/**
 * Builds the plugin's functional route table from the autowired Forge services.
 *
 * Kept separate from the PF4J [io.whozoss.factory.forge.plugin.ForgeRouteContributor]
 * so the route table can be exercised without a live PF4J extension.
 */
object ForgeRouteContributorRoutes {

    fun build(
        runService: ForgeRunService,
        gateService: ForgeGateService,
        storyService: StoryOperationService,
        jiraClient: JiraClient,
        proxy: AgentOsProxyClient,
        tenantScopeProvider: TenantScopeProvider,
    ): List<FactoryRoute> {
        val forge = ForgeRunController(runService, gateService, storyService, proxy, tenantScopeProvider)
        val jira = JiraProxyController(jiraClient, tenantScopeProvider)
        return ForgeRoutes.routes(forge, jira)
    }
}
