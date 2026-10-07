package io.whozoss.factory.forge.plugin

import io.whozoss.factory.FactoryServiceApplication
import io.whozoss.factory.sdk.spi.FactoryRouteContributor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.pf4j.PluginManager
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * "Core WITH plugin" integration test (W7.3 Step F.3).
 *
 * It boots the real `factory-service` application with the freshly built
 * `factory-forge-plugin` JAR deployed into a temporary PF4J drop-in directory,
 * then asserts the plugin's contributed routes behave exactly like the W6a
 * Surfaces:
 *   - `GET /api/forge/runs` → `400 MISSING_NAMESPACE_ID` (route mounted, query
 *     validated) instead of the `404` observed without the plugin;
 *   - `GET /api/jira/{ticketId}` → `501 JIRA_NOT_CONFIGURED` with the exact Node
 *     message.
 *
 * The plugin JAR path is provided by the Gradle `test` task as the
 * `factory.forge.plugin.jar` system property (the `test` task depends on `jar`).
 * The H2-backed `openapi` profile is used so the test does not need a database:
 * neither route touches persistence.
 *
 * This test lives in the plugin module because factory-service cannot depend on
 * the plugin (the plugin compiles against factory-service), so this is the only
 * module that can both build the JAR and boot the host.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [FactoryServiceApplication::class],
)
@ActiveProfiles("openapi")
class FactoryForgePluginIntegrationTest {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var pluginManager: PluginManager

    @Test
    fun `the plugin is discovered and its route contributor is registered`() {
        assertThat(pluginManager.plugins).isNotEmpty()
        val contributors = pluginManager.getExtensions(FactoryRouteContributor::class.java)
        assertThat(contributors).isNotEmpty()
        val paths = contributors.flatMap { it.getRoutes() }.map { it.path }
        assertThat(paths).contains("/api/forge/runs", "/api/jira/{ticketId}")
    }

    @Test
    fun `the forge runs route responds like W6a once the plugin is deployed`() {
        val response = restTemplate.getForEntity("/api/forge/runs", Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("MISSING_NAMESPACE_ID")
    }

    @Test
    fun `the jira relay returns the unconfigured contract once the plugin is deployed`() {
        val response = restTemplate.getForEntity("/api/jira/PROJ-1", Map::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.valueOf(501))
        val error = response.body?.get("error") as? Map<*, *>
        assertThat(error?.get("code")).isEqualTo("JIRA_NOT_CONFIGURED")
    }

    companion object {
        private val pluginsDir: Path = Files.createTempDirectory("factory-forge-plugin-it")

        @JvmStatic
        @DynamicPropertySource
        fun registerPluginDropIn(registry: DynamicPropertyRegistry) {
            val jarPath = System.getProperty("factory.forge.plugin.jar")
                ?: error("System property 'factory.forge.plugin.jar' must point at the built plugin JAR")
            val jar = Path.of(jarPath)
            require(Files.exists(jar)) { "Plugin JAR not found at $jar; the Gradle `jar` task must run first" }
            Files.copy(jar, pluginsDir.resolve(jar.fileName), StandardCopyOption.REPLACE_EXISTING)
            registry.add("factory.plugins.dir") { pluginsDir.toAbsolutePath().toString() }
        }
    }
}
