package io.whozoss.agentos.namespace

import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.sdk.entity.EntityMetadata
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for managing Namespaces.
 *
 * Extends [EntityController] with [NamespaceResource] as the HTTP DTO.
 *
 * Standard CRUD endpoints (inherited):
 *   GET    /api/namespaces/{id}
 *   POST   /api/namespaces/by-ids
 *   GET    /api/namespaces/by-parentId/{parentId}
 *   POST   /api/namespaces
 *   PUT    /api/namespaces/{id}
 *   DELETE /api/namespaces/{id}
 *
 * Additional endpoints:
 *   GET    /api/namespaces — list all namespaces (no parent filter needed)
 */
@RestController
@RequestMapping(
    "/api/namespaces",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class NamespaceController(
    private val namespaceService: NamespaceService,
) : EntityController<Namespace, String, NamespaceResource>(namespaceService) {

    // -------------------------------------------------------------------------
    // Mapping between domain entity and HTTP resource
    // -------------------------------------------------------------------------

    override fun toResource(entity: Namespace): NamespaceResource =
        NamespaceResource(
            id = entity.metadata.id,
            name = entity.name,
            description = entity.description,
        )

    override fun toDomain(resource: NamespaceResource): Namespace =
        Namespace(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            name = resource.name,
            description = resource.description,
        )

    // -------------------------------------------------------------------------
    // Additional endpoints
    // -------------------------------------------------------------------------

    /**
     * GET /api/namespaces — list all namespaces.
     */
    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    fun listAll(): List<NamespaceResource> {
        logger.info { "listing all namespaces" }
        return namespaceService.findAll().map { toResource(it) }
    }

    companion object : KLogging()
}
