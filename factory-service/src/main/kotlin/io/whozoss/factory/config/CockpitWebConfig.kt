package io.whozoss.factory.config

import io.whozoss.factory.web.CockpitAssets
import io.whozoss.factory.web.CockpitResourceHttpRequestHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler

/**
 * Serves the vanilla cockpit assets same-origin from `factory-service`.
 *
 * The `js` and `css` trees are each mapped to a dedicated
 * [ResourceHttpRequestHandler] at a higher precedence than Spring Boot's default
 * classpath static handler; the controller mappings stay untouched and win
 * first (their order is `0`). `cockpit.html` itself is served by
 * `CockpitController` (`/cockpit`).
 *
 * The `application/javascript` media type is registered explicitly for `.mjs`
 * (see [CockpitResourceHttpRequestHandler] for why the handler itself enforces
 * it). The assets directory is configurable (`factory.cockpit.assets-dir`).
 */
@Configuration
class CockpitWebConfig(private val assets: CockpitAssets) {

    @Bean
    fun cockpitJsResourceHandler(): ResourceHttpRequestHandler =
        cockpitHandler(listOf(assets.location("js")))

    @Bean
    fun cockpitCssResourceHandler(): ResourceHttpRequestHandler =
        cockpitHandler(listOf(assets.location("css")))

    @Bean
    fun cockpitResourceHandlerMapping(
        cockpitJsResourceHandler: ResourceHttpRequestHandler,
        cockpitCssResourceHandler: ResourceHttpRequestHandler,
    ): SimpleUrlHandlerMapping {
        val mapping = SimpleUrlHandlerMapping()
        // After the annotated controllers (order 0) but before Boot's default
        // static resource handler (order LOWEST_PRECEDENCE - 1).
        mapping.order = Ordered.LOWEST_PRECEDENCE - 10
        mapping.urlMap = mapOf<String, Any>(
            "/js/**" to cockpitJsResourceHandler,
            "/css/**" to cockpitCssResourceHandler,
        )
        return mapping
    }

    private fun cockpitHandler(locations: List<Resource>): ResourceHttpRequestHandler =
        CockpitResourceHttpRequestHandler().apply {
            setLocations(locations)
            // `MediaTypeFactory`/embedded containers report `.mjs` as
            // `text/javascript` or `application/octet-stream`; register the
            // canonical value explicitly (the handler enforces it too).
            setMediaTypes(mapOf("mjs" to MediaType.valueOf("application/javascript")))
        }
}
