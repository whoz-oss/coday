package io.whozoss.factory.plugin

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.sdk.spi.FactoryRouteContributor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.pf4j.PluginManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.function.RouterFunction

/**
 * Verifies the PF4J plugin host boots cleanly with an empty `plugins/`
 * directory: the core routes respond, and the optional Forge plugin surfaces are
 * absent (`404`) until the plugin JAR is deployed.
 *
 * The `factory.plugins.dir` property is pinned to a temp directory by
 * [io.whozoss.factory.PostgresContainerSpec], so this test always exercises the
 * "core without plugin" contract.
 */
class FactoryPluginSystemIntegrationTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var pluginManager: PluginManager

    @Autowired
    private lateinit var applicationContext: ApplicationContext

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `the application starts with an empty plugins directory and PF4J is initialised`() {
        assertThat(pluginManager).isNotNull
        assertThat(pluginManager.plugins).isEmpty()
        assertThat(pluginManager.getExtensions(FactoryRouteContributor::class.java)).isEmpty()
    }

    @Test
    fun `the plugin router is mounted even when no plugin contributes a route`() {
        val router = applicationContext.getBean("pluginRouterFunction")
        assertThat(router).isInstanceOf(RouterFunction::class.java)
    }

    @Test
    fun `core workstream route still returns 200 without the plugin`() {
        val response = restTemplate.getForEntity(
            "/api/factory/workstreams?namespaceId=test",
            Array<Any>::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `core agentos relay route is still mapped without the plugin`() {
        val response = restTemplate.getForEntity("/api/agents", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("MISSING_NAMESPACE_ID")
    }

    @Test
    fun `forge routes are absent when the plugin is not deployed`() {
        val response = restTemplate.getForEntity("/api/forge/runs", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `jira routes are absent when the plugin is not deployed`() {
        val response = restTemplate.getForEntity("/api/jira/PROJ-1", Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
