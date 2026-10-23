package io.whozoss.factory.web

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * Compatibility redirects to the sole Factory UI, Cockpit V2.
 *
 * The legacy vanilla dashboard assets are no longer served. Keeping these
 * redirects avoids breaking saved entry-point URLs while ensuring every
 * browser entry reaches the Angular cockpit.
 */
@Hidden
@Controller
class CockpitController {

    @GetMapping("/")
    fun root(): String = "redirect:/cockpit-v2"

    @GetMapping("/cockpit", "/cockpit/", "/cockpit.html")
    fun cockpit(): String = "redirect:/cockpit-v2"
}
