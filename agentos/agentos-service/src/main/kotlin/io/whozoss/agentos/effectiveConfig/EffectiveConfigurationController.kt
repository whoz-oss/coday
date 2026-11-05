package io.whozoss.agentos.effectiveConfig

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Read-only view of the effective configuration of the running service, for the administration page.
 *
 * Super-admin only. Secrets never leave the server: see [EffectiveConfigurationService].
 */
@RestController
@RequestMapping("/api/admin/configuration", produces = [MediaType.APPLICATION_JSON_VALUE])
class EffectiveConfigurationController(
    private val service: EffectiveConfigurationService,
) {
    @GetMapping
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    @Operation(
        operationId = "listEffectiveConfiguration",
        summary = "List the effective configuration (environment variables and Spring properties) of the service",
    )
    fun list(): List<ConfigurationEntry> = service.list()
}
