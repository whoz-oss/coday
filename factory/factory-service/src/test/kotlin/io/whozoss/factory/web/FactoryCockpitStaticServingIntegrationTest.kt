package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryCockpitProperties
import io.whozoss.factory.config.FactoryCockpitWebConfig
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.error.FactoryExceptionHandler
import io.whozoss.factory.web.security.SecurityConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path

/** HTTP tests of the Factory Cockpit same-origin hosting under `/factory`. */
@WebMvcTest(controllers = [FactoryCockpitController::class, CockpitController::class])
@EnableConfigurationProperties(FactoryProperties::class, FactoryCockpitProperties::class)
@Import(
    FactoryCockpitAssets::class,
    FactoryCockpitWebConfig::class,
    AuthenticationFilter::class,
    JwtTokenVerifier::class,
    LocalDevMembershipResolver::class,
    SecurityConfiguration::class,
    FactoryExceptionHandler::class,
)
class FactoryCockpitStaticServingIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `serves the Spa shell at slash factory`() {
        mockMvc.get("/factory").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
            content { string(containsString("<sf-root>")) }
        }
    }

    @Test
    fun `falls back to the Spa shell for a client route`() {
        mockMvc.get("/factory/sandboxes").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }
        mockMvc.get("/factory/sessions/872641a8").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }
    }

    @Test
    fun `serves an existing hashed javascript asset`() {
        mockMvc.get("/factory/main-abc123.js").andExpect {
            status { isOk() }
            content { string(containsString("console.log('factory-cockpit')")) }
        }
    }

    @Test
    fun `returns 404 and never Html for a missing javascript asset`() {
        mockMvc.get("/factory/non-existent-xyz.js").andExpect {
            status { isNotFound() }
            content { string(not(containsString("<!doctype html"))) }
        }
    }

    @Test
    fun `returns 404 and never Html for a missing stylesheet asset`() {
        mockMvc.get("/factory/non-existent-xyz.css").andExpect {
            status { isNotFound() }
            content { string(not(containsString("<!doctype html"))) }
        }
    }

    @Test
    fun `redirects former entry points to Factory Cockpit`() {
        listOf("/", "/cockpit", "/cockpit/", "/cockpit.html").forEach { path ->
            mockMvc.get(path).andExpect {
                status { is3xxRedirection() }
                redirectedUrl("/factory")
            }
        }
    }

    @Test
    fun `does not expose the removed cockpit v2 route`() {
        mockMvc.get("/cockpit-v2").andExpect {
            status { isNotFound() }
        }
    }

    companion object {
        private val cockpitDir: Path = Files.createTempDirectory("factory-cockpit-assets").also { root ->
            Files.writeString(
                root.resolve("index.html"),
                "<!doctype html><html><head><title>Factory Cockpit</title></head><body><sf-root></sf-root></body></html>",
            )
            Files.writeString(root.resolve("main-abc123.js"), "console.log('factory-cockpit')")
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerAssets(registry: DynamicPropertyRegistry) {
            registry.add("factory.cockpit.assets-dir") { cockpitDir.toAbsolutePath().toString() }
        }
    }
}
