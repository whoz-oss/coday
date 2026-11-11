package io.whozoss.factory.forge.plugin

import com.fasterxml.jackson.databind.ObjectMapper
import io.whozoss.factory.sdk.spi.FactoryBriefContributor
import io.whozoss.factory.sdk.spi.FactoryCallerIdentity
import io.whozoss.factory.sdk.spi.FactoryFlowCatalogContributor
import io.whozoss.factory.sdk.spi.FactoryFlowSelector
import io.whozoss.factory.sdk.spi.FactoryRouteContributor
import io.whozoss.factory.sdk.spi.FactoryRouteRequest
import io.whozoss.factory.sdk.spi.FactoryRunLifecycleObserver
import io.whozoss.factory.sdk.spi.FactoryStepResultValidator
import io.whozoss.factory.sdk.spi.FactoryWorkflowExecutionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.pf4j.DefaultPluginManager
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Standalone PF4J integration test of `factory-forge-plugin`.
 *
 * Verifies that:
 * 1. The plugin compiles and loads purely against `factory-sdk` (no dependency on
 *    `factory-service`).
 * 2. All PF4J extensions (`ForgeRouteContributor`, `ForgeWorkflowExecutionPolicy`,
 *    `ForgeBmadCatalogContributor`, `ForgeBmadFlowSelector`,
 *    `ForgeBmadStepResultValidator`, `ForgeBmadBriefContributor`,
 *    `ForgeBmadLifecycleObserver`) are discovered from the built JAR and
 *    instantiated via the plugin's own `ForgePlugin` Spring child context.
 * 3. Dead `/api/forge/runs` routes are gone, while `/api/jira/{ticketId}` and
 *    `/api/factory/jira/{ticketId}` are contributed and return the expected
 *    `501 JIRA_NOT_CONFIGURED` / `401 UNAUTHENTICATED` envelopes through
 *    `FactoryCallerIdentity`.
 */
class FactoryForgePluginIntegrationTest {

    private val mapper = ObjectMapper()
    private lateinit var pluginsDir: Path
    private lateinit var pluginManager: DefaultPluginManager

    @BeforeEach
    fun setUp() {
        pluginsDir = Files.createTempDirectory("factory-forge-plugin-it")
        val jarPath = System.getProperty("factory.forge.plugin.jar")
            ?: error("System property 'factory.forge.plugin.jar' must point at the built plugin JAR")
        val jar = Path.of(jarPath)
        require(Files.exists(jar)) { "Plugin JAR not found at $jar; the Gradle `jar` task must run first" }
        Files.copy(jar, pluginsDir.resolve(jar.fileName), StandardCopyOption.REPLACE_EXISTING)

        pluginManager = ForgeTestPluginManager(pluginsDir)
        pluginManager.loadPlugins()
        pluginManager.startPlugins()
    }

    @AfterEach
    fun tearDown() {
        runCatching { pluginManager.stopPlugins() }
        runCatching { pluginManager.unloadPlugins() }
    }

    @Test
    fun `the plugin JAR loads and registers Jira routes, execution policy and all 5 BMAD flow SPIs`() {
        assertThat(pluginManager.plugins).hasSize(1)

        val policies = pluginManager.getExtensions(
            FactoryWorkflowExecutionPolicy::class.java,
            "factory-forge-plugin",
        )
        assertThat(policies.map { it.getPluginId() }).containsExactly("forge")

        val catalogContributors = pluginManager.getExtensions(
            FactoryFlowCatalogContributor::class.java,
            "factory-forge-plugin",
        )
        assertThat(catalogContributors).hasSize(1)
        assertThat(catalogContributors.single().flows()).hasSize(10)

        val selectors = pluginManager.getExtensions(
            FactoryFlowSelector::class.java,
            "factory-forge-plugin",
        )
        assertThat(selectors).hasSize(1)
        assertThat(selectors.single().runTypes()).contains("forge-controller-v1")

        val validators = pluginManager.getExtensions(
            FactoryStepResultValidator::class.java,
            "factory-forge-plugin",
        )
        assertThat(validators).hasSize(1)
        assertThat(validators.single().supports("forge.product-spec", "product-specification", "ForgeProductWorker")).isTrue()

        val briefContributors = pluginManager.getExtensions(
            FactoryBriefContributor::class.java,
            "factory-forge-plugin",
        )
        assertThat(briefContributors).hasSize(1)

        val observers = pluginManager.getExtensions(
            FactoryRunLifecycleObserver::class.java,
            "factory-forge-plugin",
        )
        assertThat(observers).hasSize(1)

        val contributors = pluginManager.getExtensions(
            FactoryRouteContributor::class.java,
            "factory-forge-plugin",
        )
        assertThat(contributors).hasSize(1)
        val routes = contributors.flatMap { it.getRoutes() }
        val paths = routes.map { it.path }

        assertThat(paths).containsExactlyInAnyOrder(
            "/api/jira/{ticketId}",
            "/api/factory/jira/{ticketId}",
        )
        assertThat(paths).doesNotContain("/api/forge/runs")
    }

    @Test
    fun `the Jira route enforces caller identity and returns 501 JIRA_NOT_CONFIGURED when unconfigured`() {
        val route = pluginManager
            .getExtensions(FactoryRouteContributor::class.java, "factory-forge-plugin")
            .flatMap { it.getRoutes() }
            .first { it.path == "/api/jira/{ticketId}" }

        // Unauthenticated request -> 401 UNAUTHENTICATED
        val unauthResponse = route.handler.handle(
            FactoryRouteRequest(
                method = "GET",
                path = "/api/jira/PROJ-1",
                pathVariables = mapOf("ticketId" to "PROJ-1"),
                caller = null,
            ),
        )
        assertThat(unauthResponse.status).isEqualTo(401)
        val unauthBody = mapper.readValue(unauthResponse.body, Map::class.java)
        val unauthError = unauthBody["error"] as Map<*, *>
        assertThat(unauthError["code"]).isEqualTo("UNAUTHENTICATED")

        // Authenticated request with no Jira credentials -> 501 JIRA_NOT_CONFIGURED
        val response = route.handler.handle(
            FactoryRouteRequest(
                method = "GET",
                path = "/api/jira/PROJ-1",
                pathVariables = mapOf("ticketId" to "PROJ-1"),
                caller = FactoryCallerIdentity(userId = "user-1", organizationId = "org-1"),
            ),
        )
        assertThat(response.status).isEqualTo(501)
        val body = mapper.readValue(response.body, Map::class.java)
        val error = body["error"] as Map<*, *>
        assertThat(error["code"]).isEqualTo("JIRA_NOT_CONFIGURED")
    }
}
