package io.whozoss.factory.web

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Browser entry point of the Cockpit V2 SPA (Angular) served same-origin by
 * `factory-service`.
 *
 * `GET /cockpit-v2` (and every client-side route below it) returns the SPA
 * shell `index.html`, so a deep-link or an in-app refresh always boots the
 * Angular router.
 *
 * The hashed static assets (`*.js`, `*.css`, …) are *not* handled here: they are
 * matched at a higher precedence by [io.whozoss.factory.config.CockpitV2WebConfig]
 * and served by a dedicated resource handler. This distinction guarantees the
 * SPA fallback never answers an HTML shell for a missing `.js`/`.css` asset — a
 * browser that receives HTML where it expects JavaScript fails loudly, so a
 * missing asset must stay a `404`.
 *
 * The legacy `/cockpit` surface is untouched (see `CockpitController`).
 */
@Hidden
@Controller
class CockpitV2Controller(private val assets: CockpitV2Assets) {

    @GetMapping("/cockpit-v2", "/cockpit-v2/", "/cockpit-v2/**")
    fun app(request: HttpServletRequest): ResponseEntity<Resource> {
        val relativePath = relativePath(request)
        val lastSegment = relativePath.substringAfterLast('/')
        if (STATIC_EXTENSIONS.any { lastSegment.endsWith(".$it", ignoreCase = true) }) {
            // Safety net: asset-looking paths are normally handled by the static
            // resource handler, but a nested one (e.g. `/cockpit-v2/a/b.js`)
            // must never be answered with the HTML shell.
            factoryError(404, "COCKPIT_V2_ASSET_NOT_FOUND", "Cockpit V2 asset not found: $relativePath")
        }
        return indexHtml()
    }

    private fun indexHtml(): ResponseEntity<Resource> {
        val resource = assets.resolve("index.html")
            ?: factoryError(404, "COCKPIT_V2_ASSETS_MISSING", "Cockpit V2 assets are not deployed.")
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(resource)
    }

    private fun relativePath(request: HttpServletRequest): String =
        request.requestURI.removePrefix("/cockpit-v2").trimStart('/')

    private companion object {
        val STATIC_EXTENSIONS: Set<String> = setOf(
            "js", "mjs", "css", "map", "json",
            "txt", "ico", "svg", "png", "webp", "woff", "woff2",
        )
    }
}
