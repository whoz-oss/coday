package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityController
import org.springframework.http.HttpStatus
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
@RequestMapping("/api/namespaces")
class NamespaceController(
    private val namespaceService: NamespaceService,
) : EntityController<NamespaceModel, Unit>(namespaceService) {

    /**
     * List all namespaces.
     *
     * GET /api/namespaces
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun listAll(): List<NamespaceModel> = namespaceService.findAll()
}
