package io.whozoss.factory.web

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Entry points of the vanilla cockpit served same-origin by `factory-service`.
 *
 * `GET /` redirects to `GET /cockpit`, which returns the `cockpit.html` shell;
 * the shell then loads `/js/app.mjs`, the `/css` tree and the relative `/api`
 * endpoints from the same origin.
 *
 * Hidden from the generated OpenAPI document: this is a browser entry point,
 * not a JSON operation.
 */
@Hidden
@Controller
class CockpitController(private val assets: CockpitAssets) {

    @GetMapping("/")
    fun root(): String = "redirect:/cockpit"

    @GetMapping("/cockpit", "/cockpit/", "/cockpit.html")
    fun cockpit(): ResponseEntity<Resource> {
        val resource = assets.resolve("cockpit.html")
            ?: factoryError(404, "COCKPIT_ASSETS_MISSING", "Cockpit assets are not deployed.")
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(resource)
    }
}
