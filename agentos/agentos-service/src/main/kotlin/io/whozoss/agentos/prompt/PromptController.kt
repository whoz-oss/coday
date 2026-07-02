package io.whozoss.agentos.prompt

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for [Prompt] entities at /api/prompts.
 *
 * Listing uses the standard `by-parentId` pattern with namespace READ permission.
 *
 * Scope dispatch on POST is inferred from body.namespaceId:
 * - null     -> platform scope (Super Admin only)
 * - non-null -> namespace-scoped (WRITE permission on namespace required)
 *
 * Authorization on PUT / DELETE is fully handled by @PreAuthorize("hasPermission(#id, 'Prompt', ACTION)").
 * PermissionServiceImpl enforces that platform-level entities (namespaceId == null) require Super Admin
 * for WRITE/DELETE — no additional check is needed in the controller body.
 *
 * Mass-assignment guard on PUT: [namespaceId] is immutable post-create.
 * Mutable fields: name, description, content, parameters.
 */
@RestController
@RequestMapping(
    "/api/prompts",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class PromptController(
    private val promptService: PromptService,
    private val namespaceService: NamespaceService,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<Prompt, UUID, PromptResource>(promptService, userService, permissionService) {
    override val entityType = EntityType.PROMPT

    override fun toResource(entity: Prompt): PromptResource =
        PromptResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            name = entity.name,
            description = entity.description,
            content = entity.content,
            parameters =
                entity.parameters.map { p ->
                    PromptParameterResource(
                        name = p.name,
                        description = p.description,
                        defaultValue = p.defaultValue,
                    )
                },
            createdBy = entity.metadata.createdBy,
            createdOn = entity.metadata.created,
            updatedBy = entity.metadata.modifiedBy,
            updatedOn = entity.metadata.modified,
        )

    override fun toDomain(resource: PromptResource): Prompt =
        Prompt(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            name = resource.name,
            description = resource.description,
            content = resource.content,
            parameters = resource.parameters.map { it.toDomain() },
        )

    private fun toDomainForUpdate(
        resource: PromptResource,
        existing: Prompt,
    ): Prompt =
        existing.copy(
            name = resource.name,
            description = resource.description,
            content = resource.content,
            parameters = resource.parameters.map { it.toDomain() },
        )

    private fun PromptParameterResource.toDomain(): PromptParameter =
        PromptParameter(
            name = name,
            description = description,
            defaultValue = defaultValue,
        )

    // -------------------------------------------------------------------------
    // Read endpoints
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Prompt', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): PromptResource = super.getById(id)

    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: GetByIdsRequest,
    ): List<PromptResource> = super.getByIds(request)

    /**
     * List all prompts belonging to a namespace.
     *
     * Uses the standard `by-parentId` pattern with READ permission on the namespace.
     * Platform-level prompts (namespaceId == null) are not returned by this endpoint.
     */
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("hasPermission(#parentId, 'Namespace', 'READ')")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<PromptResource> = promptService.findByParent(parentId).map { toResource(it) }

    /**
     * GET /api/prompts/platform
     *
     * Lists all non-removed platform-level prompts (namespaceId = null).
     *
     * Readable by any authenticated user — consistent with `GET /{id}` which grants READ
     * to every authenticated caller for platform-scoped prompts via [isPlatformScoped]
     * in [Neo4jPermissionRepository].
     */
    @GetMapping("/platform")
    @PreAuthorize("isAuthenticated()")
    fun listPlatformPrompts(): List<PromptResource> = promptService.findPlatform().map { toResource(it) }

    // -------------------------------------------------------------------------
    // Write endpoints
    // -------------------------------------------------------------------------

    @Operation(
        summary = "Create a Prompt",
        description =
            "Scope is inferred from body.namespaceId:\n\n" +
                "| body.namespaceId | scope     | required permission        |\n" +
                "|------------------|-----------|----------------------------|\n" +
                "| null             | platform  | Super Admin only           |\n" +
                "| present          | namespace | WRITE on the namespace     |\n\n" +
                "A namespaceId that does not exist returns 404.",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: PromptResource,
    ): PromptResource {
        val resolvedNs: UUID? = resource.namespaceId
        val isPlatform = resolvedNs == null
        val currentUser = userService.getCurrentUser()

        // Authorization - run BEFORE existence check to avoid leaking namespace existence.
        if (isPlatform) {
            if (!currentUser.isAdmin) {
                throw AccessDeniedException("Platform-level Prompt requires Super Admin")
            }
        } else {
            val granted =
                permissionService.hasPermission(
                    userId = currentUser.id.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = resolvedNs.toString(),
                    action = io.whozoss.agentos.permissions.Action.WRITE,
                )
            if (!granted) {
                throw AccessDeniedException("Cannot create Prompt in namespace $resolvedNs (WRITE required)")
            }
        }

        // Namespace existence check (deferred after authz to avoid 404 existence oracle).
        if (resolvedNs != null && namespaceService.findById(resolvedNs) == null) {
            throw ResourceNotFoundException("Namespace not found: $resolvedNs")
        }

        val target =
            Prompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = resolvedNs,
                name = resource.name,
                description = resource.description,
                content = resource.content,
                parameters = resource.parameters.map { it.toDomain() },
            )
        return toResource(promptService.create(target))
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Prompt', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: PromptResource,
    ): PromptResource {
        val existing =
            promptService.findById(id)
                ?: throw ResourceNotFoundException("Prompt not found: $id")
        return toResource(promptService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Prompt', 'DELETE')")
    @HideOnAccessDenied
    override fun delete(
        @PathVariable id: UUID,
    ) {
        val existing =
            promptService.findById(id)
                ?: throw ResourceNotFoundException("Prompt not found: $id")
        super.delete(id)
    }

    companion object : KLogging()
}
