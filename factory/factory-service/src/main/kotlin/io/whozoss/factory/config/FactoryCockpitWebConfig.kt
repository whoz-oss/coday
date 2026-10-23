package io.whozoss.factory.config

import io.whozoss.factory.web.FactoryCockpitAssets
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.MediaType
import org.springframework.web.servlet.handler.SimpleUrlHandlerMapping
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler

/** Serves the Factory Cockpit Angular assets under `/factory`. */
@Configuration
class FactoryCockpitWebConfig(private val assets: FactoryCockpitAssets) {

    @Bean
    fun factoryCockpitResourceHandler(): ResourceHttpRequestHandler =
        ResourceHttpRequestHandler().apply {
            setLocations(listOf(assets.location()))
            setMediaTypes(mapOf("mjs" to MediaType.valueOf("application/javascript")))
        }

    @Bean
    fun factoryCockpitResourceHandlerMapping(
        factoryCockpitResourceHandler: ResourceHttpRequestHandler,
    ): SimpleUrlHandlerMapping {
        val mapping = SimpleUrlHandlerMapping()
        mapping.order = Ordered.HIGHEST_PRECEDENCE
        val urlMap = mutableMapOf<String, Any>()
        ASSET_PATTERNS.forEach { pattern -> urlMap[pattern] = factoryCockpitResourceHandler }
        mapping.urlMap = urlMap
        return mapping
    }

    private companion object {
        val ASSET_PATTERNS: List<String> = listOf(
            "/factory/*.js",
            "/factory/*.mjs",
            "/factory/*.css",
            "/factory/*.map",
            "/factory/*.json",
            "/factory/*.txt",
            "/factory/*.ico",
            "/factory/*.svg",
            "/factory/*.png",
            "/factory/*.webp",
            "/factory/*.woff",
            "/factory/*.woff2",
            "/factory/media/**",
        )
    }
}
