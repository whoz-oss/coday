package io.whozoss.factory.web

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Browser entry point of the Factory Cockpit SPA (Angular) served same-origin
 * by `factory-service` under `/factory`.
 *
 * `GET /factory` (and every client-side route below it) returns the SPA shell
 * `index.html`, so a deep-link or an in-app refresh always boots the Angular
 * router.
 *
 * Hashed static assets are handled at a higher precedence by
 * [io.whozoss.factory.config.FactoryCockpitWebConfig]. A missing asset must remain a
 * `404` rather than being answered with the HTML shell.
 */
@Hidden
@Controller
class FactoryCockpitController(private val assets: FactoryCockpitAssets) {

    @GetMapping("/factory", "/factory/", "/factory/**")
    fun app(request: HttpServletRequest): ResponseEntity<Resource> {
        val relativePath = relativePath(request)
        val lastSegment = relativePath.substringAfterLast('/')
        if (STATIC_EXTENSIONS.any { lastSegment.endsWith(".$it", ignoreCase = true) }) {
            factoryError(404, "FACTORY_COCKPIT_ASSET_NOT_FOUND", "Factory Cockpit asset not found: $relativePath")
        }
        return indexHtml()
    }

    private fun indexHtml(): ResponseEntity<Resource> {
        val resource = assets.resolve("index.html")
            ?: factoryError(404, "FACTORY_COCKPIT_ASSETS_MISSING", "Factory Cockpit assets are not deployed.")
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(resource)
    }

    private fun relativePath(request: HttpServletRequest): String =
        request.requestURI.removePrefix("/factory").trimStart('/')

    private companion object {
        val STATIC_EXTENSIONS: Set<String> = setOf(
            "js", "mjs", "css", "map", "json",
            "txt", "ico", "svg", "png", "webp", "woff", "woff2",
        )
    }
}
