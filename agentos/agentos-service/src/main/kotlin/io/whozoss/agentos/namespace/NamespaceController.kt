package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityController
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * REST API for managing Namespaces.
 *
 * Extends EntityController for standard CRUD:
 *   GET    /api/namespaces/{id}
 *   POST   /api/namespaces
 *   PUT    /api/namespaces/{id}
 *   DELETE /api/namespaces/{id}
 *
 * Overrides the list endpoint to return all namespaces (no parent filter needed).
 */
@RestController
@RequestMapping(
    "/api/namespaces",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)

class NamespaceController(
    private val namespaceService: NamespaceService,
) : EntityController<Namespace, Unit>(namespaceService) {
    /**
     * List all namespaces.
     *
     * GET /api/namespaces/list
     */
    @GetMapping("/list")
    @ResponseStatus(HttpStatus.OK)
    fun listAll(): List<Namespace> {
        logger.info { "calling all namespaces" }
        return namespaceService.findAll()
    }

    companion object : KLogging()
}
