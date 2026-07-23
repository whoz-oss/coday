package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationTypeApi
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationTypeDescriptor
import mu.KLogging
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST API exposing the catalogue of available integration types.
 * Implements [IntegrationTypeApi] so external consumers can declare a Feign client
 * against the SDK interface.
 */
@RestController
@RequestMapping(
    "/api/integration-types",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationTypeController(
    private val integrationTypeRegistry: IntegrationTypeRegistry,
) : IntegrationTypeApi {

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    override fun listTypes(): List<IntegrationTypeDescriptor> =
        integrationTypeRegistry.listTypes()

    @GetMapping("/{type}")
    @PreAuthorize("isAuthenticated()")
    override fun getType(@PathVariable type: String): IntegrationTypeDescriptor =
        integrationTypeRegistry.findByType(type)
            ?: throw ResourceNotFoundException("Unknown integration type: $type")

    companion object : KLogging()
}
