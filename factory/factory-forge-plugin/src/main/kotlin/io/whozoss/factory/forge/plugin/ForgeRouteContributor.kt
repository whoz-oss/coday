package io.whozoss.factory.forge.plugin

import io.whozoss.factory.proxy.AgentOsProxyClient
import io.whozoss.factory.forge.port.JiraClient
import io.whozoss.factory.forge.service.ForgeGateService
import io.whozoss.factory.forge.service.ForgeRunService
import io.whozoss.factory.forge.service.StoryOperationService
import io.whozoss.factory.forge.web.ForgeRouteContributorRoutes
import io.whozoss.factory.persistence.TenantScopeProvider
import io.whozoss.factory.sdk.spi.FactoryRoute
import io.whozoss.factory.sdk.spi.FactoryRouteContributor
import org.pf4j.Extension

/**
 * PF4J extension exposing the Forge `/api/forge/...` and `/api/jira/...` routes.
 *
 * The host discovers this class through `META-INF/extensions.idx` and mounts the
 * routes via its dynamic router. The instance is autowired from the plugin's
 * child context (services) and the host parent context (AgentOS proxy, tenant
 * scope provider).
 */
@Extension
class ForgeRouteContributor(
    runService: ForgeRunService,
    gateService: ForgeGateService,
    storyService: StoryOperationService,
    jiraClient: JiraClient,
    proxy: AgentOsProxyClient,
    tenantScopeProvider: TenantScopeProvider,
) : FactoryRouteContributor {

    private val routes: List<FactoryRoute> = ForgeRouteContributorRoutes.build(
        runService = runService,
        gateService = gateService,
        storyService = storyService,
        jiraClient = jiraClient,
        proxy = proxy,
        tenantScopeProvider = tenantScopeProvider,
    )

    override fun getRoutes(): List<FactoryRoute> = routes
}
