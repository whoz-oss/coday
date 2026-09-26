package io.whozoss.factory.web

import io.whozoss.factory.error.ResourceNotFoundException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Test-only controller exercising the HTTP boundary.
 *
 * Annotated with `@RestController` so Spring's standalone handler mapping
 * detects it, but gated behind the `boundary-test` profile so it is never
 * registered in the production or integration context.
 */
@RestController
@Profile("boundary-test")
@RequestMapping("/test")
class BoundaryTestController {

    private val adminGuard = AdminGuard()

    @GetMapping("/context", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun context(request: HttpServletRequest): TrustContext =
        TrustContextFilter.from(request) ?: TrustContext.anonymous()

    @GetMapping("/admin", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun admin(request: HttpServletRequest): Map<String, Any?> {
        adminGuard.requireAdminRole(TrustContextFilter.from(request))
        return mapOf("authorized" to true)
    }

    @GetMapping("/boom", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun boom(): Nothing = throw IllegalStateException("boom")

    @GetMapping("/not-found", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun notFound(): Nothing = throw ResourceNotFoundException("missing resource")

    @PostMapping("/illegal", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun illegal(): Nothing = throw IllegalArgumentException("bad arg")
}
