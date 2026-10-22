package io.whozoss.factory.web

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/** Compatibility redirects to the Factory Cockpit served under `/factory`. */
@Hidden
@Controller
class CockpitController {

    @GetMapping("/")
    fun root(): String = "redirect:/factory"

    @GetMapping("/cockpit", "/cockpit/", "/cockpit.html")
    fun cockpit(): String = "redirect:/factory"
}
