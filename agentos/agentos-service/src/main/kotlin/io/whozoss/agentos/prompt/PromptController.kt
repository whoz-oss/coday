package io.whozoss.agentos.prompt

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.entity.OverlayScope
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
 * **Scope dispatch on POST** — inferred from `(body.namespaceId, body.userId)`:
 * - `(null, null)`   → platform (Super Admin only)
 * - `(ns, null)`     → namespace-scoped (WRITE on namespace)
 * - `(null, user)`   → user-global (authenticated only)
 * - `(ns, user)`     → user × namespace (READ on namespace)
 *
 * `body.userId` must equal the authenticated user's id when supplied (mass-assignment guard).
 *
 * Authorization on PUT / DELETE is fully handled by @PreAuthorize("hasPermission(#id, 'Prompt', ACTION)").
 *
 * Mass-assignment guard on PUT: [namespaceId] and [userId] are immutable post-create.
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
            userId = entity.userId,
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
            userId = resource.userId,
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

    @Operation(
        summary = "List Prompts by explicit scope",
        description = "Returns prompts for the given scope. `namespaceId` is required for " +
            "`NAMESPACE` and `USER_NAMESPACE` scopes, and must be omitted for `PLATFORM` and `USER`.\n\n" +
            "| scope          | namespaceId | returned prompts                  | required permission       |\n" +
            "|----------------|-------------|-----------------------------------|---------------------------|\n" +
            "| PLATFORM       | absent      | platform-level (null, null)       | authenticated             |\n" +
            "| NAMESPACE      | required    | namespace-shared (ns, null)       | READ on namespace         |\n" +
            "| USER           | absent      | user-global (null, me)            | authenticated             |\n" +
            "| USER_NAMESPACE | required    | user × namespace (ns, me)        | READ on namespace         |",
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam scope: OverlayScope,
        @RequestParam(required = false) namespaceId: UUID?,
    ): List<PromptResource> {
        validateScopeConstraints(scope, namespaceId)
        val currentUser = userService.getCurrentUser()

        return when (scope) {
            OverlayScope.PLATFORM -> promptService.findPlatform()

            OverlayScope.NAMESPACE -> {
                val granted = permissionService.hasPermission(
                    userId = currentUser.id.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = namespaceId!!.toString(),
                    action = Action.READ,
                )
                if (!granted) throw AccessDeniedException("Cannot read prompts in namespace $namespaceId")
                promptService.findByParent(namespaceId)
            }

            OverlayScope.USER -> promptService.findByUserId(currentUser.id)
                .filter { it.namespaceId == null }

            OverlayScope.USER_NAMESPACE -> {
                val granted = permissionService.hasPermission(
                    userId = currentUser.id.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = namespaceId!!.toString(),
                    action = Action.READ,
                )
                if (!granted) throw AccessDeniedException("Cannot read prompts in namespace $namespaceId")
                promptService.findByUserId(currentUser.id)
                    .filter { it.namespaceId == namespaceId }
            }
        }.map { toResource(it) }
    }

    @Operation(
        summary = "Effective prompts for a namespace",
        description = "Returns the resolved set of prompts accessible in the given namespace context. " +
            "Merges platform, namespace-shared, user-global and user×namespace layers by name, " +
            "highest-priority layer wins. Requires READ on the namespace.",
    )
    @GetMapping("/effective")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'READ')")
    fun effective(
        @RequestParam namespaceId: UUID,
    ): List<PromptResource> {
        val currentUser = userService.getCurrentUser()
        return promptService.findEffective(namespaceId, currentUser.id).map { toResource(it) }
    }

    // -------------------------------------------------------------------------
    // Write endpoints
    // -------------------------------------------------------------------------

    @Operation(
        summary = "Create a Prompt",
        description =
            "Scope is inferred from `(body.namespaceId, body.userId)`:\n\n" +
                "| body.namespaceId | body.userId      | scope           | required permission        |\n" +
                "|------------------|------------------|-----------------|----------------------------|\n" +
                "| null             | null             | platform        | Super Admin only           |\n" +
                "| present          | null             | namespace       | WRITE on the namespace     |\n" +
                "| null             | <currentUser.id> | user-global     | authenticated only         |\n" +
                "| present          | <currentUser.id> | user×namespace  | READ on the namespace      |\n\n" +
                "`body.userId` must equal the authenticated user's id when supplied. " +
                "A `namespaceId` that does not exist returns 404.",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: PromptResource,
    ): PromptResource {
        val currentUser = userService.getCurrentUser()
        val me = currentUser.id

        // Phase 1 — mass-assignment guard
        if (resource.userId != null && resource.userId != me) {
            throw BadRequestException("userId in body must match authenticated user or be omitted")
        }

        // Phase 2 — scope determination
        val resolvedNs: UUID? = resource.namespaceId
        val resolvedUser: UUID? = if (resource.userId != null) me else null
        val isPlatform = resolvedNs == null && resolvedUser == null

        // Phase 3 — per-scope authorization, run BEFORE existence check
        when {
            isPlatform -> {
                if (!currentUser.isAdmin) {
                    throw AccessDeniedException("Platform-level Prompt requires Super Admin")
                }
            }
            resolvedNs != null -> {
                val authzAction = if (resolvedUser != null) Action.READ else Action.WRITE
                val granted = permissionService.hasPermission(
                    userId = me.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = resolvedNs.toString(),
                    action = authzAction,
                )
                if (!granted) {
                    throw AccessDeniedException(
                        "Cannot create Prompt in namespace $resolvedNs (${authzAction.name} required)",
                    )
                }
            }
            // user-global: isAuthenticated() from class-level @PreAuthorize is sufficient
        }

        // Phase 4 — namespace existence check (deferred after authz)
        if (resolvedNs != null && namespaceService.findById(resolvedNs) == null) {
            throw ResourceNotFoundException("Namespace not found: $resolvedNs")
        }

        val target =
            Prompt(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = resolvedNs,
                userId = resolvedUser,
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

    /**
     * Validates the scope/namespaceId parameter combination:
     * - NAMESPACE, USER_NAMESPACE require a non-null namespaceId.
     * - PLATFORM, USER forbid namespaceId.
     *
     * Namespace **existence** is NOT checked here — it is deferred to after the
     * permission check in [list] to avoid leaking existence to unauthorized callers
     * (same ordering as [create] Phase 3 → Phase 4).
     */
    private fun validateScopeConstraints(scope: OverlayScope, namespaceId: UUID?) {
        when (scope) {
            OverlayScope.NAMESPACE, OverlayScope.USER_NAMESPACE ->
                if (namespaceId == null)
                    throw BadRequestException("namespaceId is required for scope=$scope")
            OverlayScope.PLATFORM, OverlayScope.USER ->
                if (namespaceId != null)
                    throw BadRequestException("namespaceId must not be provided for scope=$scope")
        }
    }

    companion object : KLogging()
}
