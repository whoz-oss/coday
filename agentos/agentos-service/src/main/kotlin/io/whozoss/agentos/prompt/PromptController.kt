package io.whozoss.agentos.prompt

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST API for [Prompt] entities at /api/prompts.
 *
 * Scope dispatch on POST is inferred from body.namespaceId:
 * - null     -> platform scope (Super Admin only)
 * - non-null -> namespace-scoped (WRITE permission on namespace required)
 *
 * Authorization on PUT / DELETE uses @PreAuthorize("hasPermission(#id, 'Prompt', ACTION)").
 *
 * Mass-assignment guard on PUT: [namespaceId] is immutable post-create.
 * Mutable fields: name, description, content, parameters.
 *
 * GET /api/prompts lists by scope:
 * - No params           -> platform prompts (authenticated only)
 * - ?namespaceId=<uuid> -> namespace prompts (READ on namespace required; empty list if denied)
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

    @Operation(
        summary = "List Prompts by scope",
        description =
            "Scope is inferred from the query params:\n\n" +
                "| query                 | mode      | required permission                            |\n" +
                "|-----------------------|-----------|------------------------------------------------|\n" +
                "| (no params)           | platform  | authenticated                                  |\n" +
                "| ?namespaceId=<uuid>   | namespace | READ on the namespace (empty list if missing)  |",
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: String?,
    ): List<PromptResource> {
        if (namespaceId == null) {
            return promptService.findPlatform().map { toResource(it) }
        }

        val resolvedNs =
            runCatching { UUID.fromString(namespaceId) }
                .getOrElse { throw BadRequestException("Invalid namespaceId: '$namespaceId'") }

        val currentUser = userService.getCurrentUser()
        val canRead =
            permissionService.hasPermission(
                userId = currentUser.id.toString(),
                entityType = EntityType.NAMESPACE,
                entityId = resolvedNs.toString(),
                action = Action.READ,
            )
        if (!canRead) return emptyList()

        return promptService.findByNamespaceId(resolvedNs).map { toResource(it) }
    }

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

        // Authorization - run BEFORE existence check to avoid leaking namespace existence.
        if (isPlatform) {
            if (!userService.getCurrentUser().isAdmin) {
                throw AccessDeniedException("Platform-level Prompt requires Super Admin")
            }
        } else {
            val currentUser = userService.getCurrentUser()
            val granted =
                permissionService.hasPermission(
                    userId = currentUser.id.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = resolvedNs.toString(),
                    action = Action.WRITE,
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
        requireAdminForPlatform(existing.namespaceId)
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
        requireAdminForPlatform(existing.namespaceId)
        super.delete(id)
    }

    /**
     * Throws AccessDeniedException when the scope is platform (namespaceId == null)
     * and the current user is not a Super Admin.
     */
    private fun requireAdminForPlatform(namespaceId: UUID?) {
        if (namespaceId != null) return
        if (!userService.getCurrentUser().isAdmin) {
            throw AccessDeniedException("Platform-level Prompt requires Super Admin")
        }
    }

    companion object : KLogging()
}
