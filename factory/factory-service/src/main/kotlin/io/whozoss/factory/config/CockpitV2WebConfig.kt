package io.whozoss.factory.config

import io.whozoss.factory.web.CockpitV2Assets
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler

/**
 * Serves the hashed Cockpit V2 (Angular) assets same-origin from
 * `factory-service`.
 *
 * The Angular application builder emits its bundle at the root of the configured
 * assets directory (`main-<hash>.js`, `styles-<hash>.css`, `polyfills-…`,
 * optional `media` tree, fonts, icons, …). Every asset-looking request below
 * `/cockpit-v2/` is routed to a dedicated [ResourceHttpRequestHandler] pointing
 * at that directory.
 *
 * The mapping is registered at [Ordered.HIGHEST_PRECEDENCE] so it is consulted
 * *before* the SPA catch-all of `CockpitV2Controller`: an existing asset is
 * served as-is, and a missing one raises `NoResourceFoundException`, which the
 * global advice turns into a `404`. The SPA shell is therefore never returned in
 * place of a missing JavaScript or stylesheet.
 *
 * This is strictly additive: the legacy `/cockpit` serving (`CockpitWebConfig`)
 * is left untouched.
 */
@Configuration
class CockpitV2WebConfig(private val assets: CockpitV2Assets) {

    @Bean
    fun cockpitV2ResourceHandler(): ResourceHttpRequestHandler =
        ResourceHttpRequestHandler().apply {
            setLocations(listOf(assets.location()))
            // Angular (or a dependency) may emit `.mjs`; register the canonical
            // JavaScript media type explicitly, as the legacy handler does.
            setMediaTypes(mapOf("mjs" to MediaType.valueOf("application/javascript")))
        }

    @Bean
    fun cockpitV2ResourceHandlerMapping(
        cockpitV2ResourceHandler: ResourceHttpRequestHandler,
    ): SimpleUrlHandlerMapping {
        val mapping = SimpleUrlHandlerMapping()
        mapping.order = Ordered.HIGHEST_PRECEDENCE
        val urlMap = mutableMapOf<String, Any>()
        ASSET_PATTERNS.forEach { pattern -> urlMap[pattern] = cockpitV2ResourceHandler }
        mapping.urlMap = urlMap
        return mapping
    }

    private companion object {
        /** Extensions emitted into the Angular build output, plus the media tree. */
        val ASSET_PATTERNS: List<String> = listOf(
            "/cockpit-v2/*.js",
            "/cockpit-v2/*.mjs",
            "/cockpit-v2/*.css",
            "/cockpit-v2/*.map",
            "/cockpit-v2/*.json",
            "/cockpit-v2/*.txt",
            "/cockpit-v2/*.ico",
            "/cockpit-v2/*.svg",
            "/cockpit-v2/*.png",
            "/cockpit-v2/*.webp",
            "/cockpit-v2/*.woff",
            "/cockpit-v2/*.woff2",
            "/cockpit-v2/media/**",
        )
    }
}
