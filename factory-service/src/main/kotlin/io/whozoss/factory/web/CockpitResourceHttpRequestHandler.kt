package io.whozoss.factory.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler

/**
 * Static-resource handler for the cockpit whose ES modules (`.mjs`) are always
 * served as `application/javascript`.
 *
 * Embedded servlet containers report `.mjs` through their own MIME map, and
 * [ResourceHttpRequestHandler] consults the servlet-context MIME map *before*
 * any explicitly registered media type — so a media-type override alone is not
 * enough. Browsers refuse to execute an ES module whose `Content-Type` is not a
 * JavaScript MIME type, which silently breaks `cockpit.html`/`app.mjs`.
 */
class CockpitResourceHttpRequestHandler : ResourceHttpRequestHandler() {

    override fun getMediaType(request: HttpServletRequest, resource: Resource): MediaType? {
        val filename = resource.filename
        if (filename != null && filename.endsWith(".mjs", ignoreCase = true)) {
            return MediaType.valueOf(APPLICATION_JAVASCRIPT)
        }
        return super.getMediaType(request, resource)
    }

    private companion object {
        const val APPLICATION_JAVASCRIPT = "application/javascript"
    }
}
