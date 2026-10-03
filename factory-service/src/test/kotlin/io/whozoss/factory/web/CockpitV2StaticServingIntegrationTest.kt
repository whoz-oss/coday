package io.whozoss.factory.web

import io.whozoss.factory.config.CockpitProperties
import io.whozoss.factory.config.CockpitV2Properties
import io.whozoss.factory.config.CockpitV2WebConfig
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.error.FactoryExceptionHandler
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

/**
 * HTTP tests of the Cockpit V2 same-origin hosting.
 *
 * Uses a MockMvc slice (no Neo4j) with two temporary asset directories so the
 * assertions do not depend on a prior `nx build cockpit-v2`:
 *
 *  1. `/cockpit-v2` (and a client route below it) returns the SPA shell as
 *     `text/html`;
 *  2. a missing `.js` / `.css` asset returns `404` and never the HTML shell;
 *  3. the legacy `/cockpit` surface still serves the vanilla cockpit unchanged.
 */
@WebMvcTest(controllers = [CockpitV2Controller::class, CockpitController::class])
@EnableConfigurationProperties(FactoryProperties::class, CockpitProperties::class, CockpitV2Properties::class)
@Import(
    CockpitV2Assets::class,
    CockpitV2WebConfig::class,
    CockpitAssets::class,
    TrustContextExtractor::class,
    LocalDevMembershipResolver::class,
    FactoryExceptionHandler::class,
)
class CockpitV2StaticServingIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `serves the Spa shell at slash cockpit-v2`() {
        mockMvc.get("/cockpit-v2").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
            content { string(containsString("<sf-root>")) }
        }
    }

    @Test
    fun `falls back to the Spa shell for a client route`() {
        mockMvc.get("/cockpit-v2/sandboxes").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }
        mockMvc.get("/cockpit-v2/sessions/872641a8").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }
    }

    @Test
    fun `serves an existing hashed javascript asset`() {
        mockMvc.get("/cockpit-v2/main-abc123.js").andExpect {
            status { isOk() }
            content { string(containsString("console.log('cockpit-v2')")) }
        }
    }

    @Test
    fun `returns 404 and never Html for a missing javascript asset`() {
        mockMvc.get("/cockpit-v2/non-existent-xyz.js").andExpect {
            status { isNotFound() }
            content { string(not(containsString("<!doctype html"))) }
        }
    }

    @Test
    fun `returns 404 and never Html for a missing stylesheet asset`() {
        mockMvc.get("/cockpit-v2/non-existent-xyz.css").andExpect {
            status { isNotFound() }
            content { string(not(containsString("<!doctype html"))) }
        }
    }

    @Test
    fun `keeps the legacy cockpit surface unchanged`() {
        mockMvc.get("/cockpit").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
            content { string(containsString("<title>Factory</title>")) }
        }
    }

    companion object {
        private val cockpitV2Dir: Path = Files.createTempDirectory("cockpit-v2-assets").also { root ->
            Files.writeString(
                root.resolve("index.html"),
                "<!doctype html><html><head><title>Cockpit V2</title></head><body><sf-root></sf-root></body></html>",
            )
            Files.writeString(root.resolve("main-abc123.js"), "console.log('cockpit-v2')")
        }

        private val cockpitDir: Path = Files.createTempDirectory("cockpit-assets").also { root ->
            Files.writeString(
                root.resolve("cockpit.html"),
                "<!doctype html><html><head><title>Factory</title></head>" +
                    "<body><div id=\"cockpit-topbar\"></div></body></html>",
            )
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerAssets(registry: DynamicPropertyRegistry) {
            registry.add("factory.cockpit.v2.assets-dir") { cockpitV2Dir.toAbsolutePath().toString() }
            registry.add("factory.cockpit.assets-dir") { cockpitDir.toAbsolutePath().toString() }
        }
    }
}
