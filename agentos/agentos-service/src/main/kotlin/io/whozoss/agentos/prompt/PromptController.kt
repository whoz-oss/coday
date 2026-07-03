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
        summary = "List Prompts by scope",
        description = "Scope is inferred from the query params:\n\n" +
            "| query                            | mode             | required permission                            |\n" +
            "|----------------------------------|------------------|------------------------------------------------|\n" +
            "| (no params)                      | platform         | authenticated                                  |\n" +
            "| `?namespaceId=<uuid>`            | NS-shared        | READ on the namespace (empty list if missing)  |\n" +
            "| `?namespaceId=<uuid>&userId=me`  | user × namespace | authenticated                                  |\n" +
            "| `?namespaceId=none&userId=me`    | user-global      | authenticated                                  |\n" +
            "| `?userId=me` (no namespace)      | all caller's     | authenticated                                  |\n\n" +
            "`userId` accepts ONLY the literal sentinel `me`. `namespaceId=none` is the sentinel for `namespaceId IS NULL`.",
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
    ): List<PromptResource> {
        val currentUser = userService.getCurrentUser()
        validateUserParam(userId)

        val resolvedNs = parseNamespaceParam(namespaceId)
        val all = promptService.findFiltered(
            namespaceId = resolvedNs,
            namespaceIsNone = namespaceId?.equals(NONE_SENTINEL, ignoreCase = true) == true,
            callerId = currentUser.id,
            userRequested = userId != null,
            canReadNamespace = { nsId ->
                permissionService.hasPermission(
                    userId = currentUser.id.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = nsId.toString(),
                    action = Action.READ,
                )
            },
        )
        return all.map { toResource(it) }
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

    private fun parseNamespaceParam(raw: String?): UUID? = when {
        raw == null -> null
        raw.equals(NONE_SENTINEL, ignoreCase = true) -> null
        else -> runCatching { UUID.fromString(raw) }
            .getOrElse { throw BadRequestException("Invalid namespaceId: '$raw'") }
    }

    private fun validateUserParam(raw: String?) {
        if (raw != null && !raw.equals(ME_SENTINEL, ignoreCase = true)) {
            throw BadRequestException(
                "Invalid userId filter: '$raw' — only 'me' is supported",
            )
        }
    }

    companion object : KLogging() {
        const val NONE_SENTINEL = "none"
        const val ME_SENTINEL = "me"
    }
}
