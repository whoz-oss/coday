package io.whozoss.agentos.prompt

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.prompt.PromptApi
import io.whozoss.agentos.sdk.api.prompt.PromptDto
import io.whozoss.agentos.sdk.api.prompt.PromptEffectiveRequest
import io.whozoss.agentos.sdk.api.prompt.PromptParameterDto
import io.whozoss.agentos.sdk.api.prompt.PromptSearchRequest
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
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

/**
 * REST API for [Prompt] entities at /api/prompts.
 *
 * Implements [PromptApi] so external consumers can declare a Feign client against
 * the SDK interface. Standard CRUD operations are delegated to [crud] explicitly.
 *
 * **Scope dispatch on POST** — inferred from `(body.namespaceId, body.userId)`:
 * - `(null, null)`   → platform (Super Admin only)
 * - `(ns, null)`     → namespace-scoped (WRITE on namespace)
 * - `(null, user)`   → user-global (authenticated only)
 * - `(ns, user)`     → user × namespace (READ on namespace)
 *
 * `body.userId` must equal the authenticated user’s id when supplied (mass-assignment guard).
 *
 * **Mass-assignment guard on PUT**: [namespaceId], [userId] and [agentConfigId] are
 * immutable post-create — preserved from the persisted entity.
 * Mutable fields: name, description, content, parameters, externalMetadata.
 *
 * **[search]** returns prompts at an exact scope level — no merge, no inheritance.
 * **[effective]** returns the merged set across the four overlay layers.
 */
@RestController
@RequestMapping(
    "/api/prompts",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class PromptController(
    private val promptService: PromptService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : PromptApi {
    private val crud =
        EntityCrudDelegate(
            service = promptService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.PROMPT,
            toResource = { toDto(it as Prompt) },
        )

    // -------------------------------------------------------------------------
    // Read endpoints
    // -------------------------------------------------------------------------

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Prompt', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): PromptDto = crud.getById(id)

    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<PromptDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @Operation(
        summary = "List Prompts at an exact scope level",
        description =
            "Returns prompts declared at a single exact scope level — no merge, no inheritance. " +
                "The `(namespaceId?, userId?)` combination in the body determines the level:\n\n" +
                "| namespaceId | userId   | level            | required permission        |\n" +
                "|-------------|----------|------------------|----------------------------|\n" +
                "| null        | null     | platform         | authenticated              |\n" +
                "| non-null    | null     | namespace-shared | READ on namespace          |\n" +
                "| null        | non-null | user-global      | authenticated              |\n" +
                "| non-null    | non-null | user×namespace   | READ on namespace          |\n\n" +
                "Optional `agentConfigIds` filter restricts results to prompts linked to those agents.",
    )
    @PostMapping(
        "/search",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun search(
        @Valid @RequestBody request: PromptSearchRequest,
    ): List<PromptDto> {
        val currentUser = userService.getCurrentUser()
        val resolvedNamespaceId = resolveOptionalNamespaceId(request.namespaceId, request.namespaceExternalId)

        // Namespace-scoped levels require READ on the namespace
        if (resolvedNamespaceId != null) {
            val granted =
                permissionService.hasPermission(
                    userId = currentUser.id.toString(),
                    entityType = EntityType.NAMESPACE,
                    entityId = resolvedNamespaceId.toString(),
                    action = Action.READ,
                )
            if (!granted) throw AccessDeniedException("Cannot read prompts in namespace $resolvedNamespaceId")
        }

        return promptService
            .findByScope(
                namespaceId = resolvedNamespaceId,
                userId = request.userId,
                agentConfigIds = request.agentConfigIds,
            ).map(::toDto)
    }

    @Operation(
        summary = "Effective prompts for a user in a namespace",
        description =
            "Returns the resolved set of prompts accessible in the given namespace context. " +
                "Merges platform, namespace-shared, user-global and user×namespace layers by name, " +
                "highest-priority layer wins. Optional `agentConfigId` filter applied post-resolution. " +
                "Requires READ on the namespace.",
    )
    @PostMapping(
        "/effective",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun effective(
        @Valid @RequestBody request: PromptEffectiveRequest,
    ): List<PromptDto> {
        val nsId = resolveNamespaceId(request.namespaceId, request.namespaceExternalId)
        val uId = resolveUserId(request.userId, request.userExternalId)

        val currentUser = userService.getCurrentUser()
        if (uId != currentUser.id) {
            throw BadRequestException("userId must match authenticated user")
        }

        val granted =
            permissionService.hasPermission(
                userId = currentUser.id.toString(),
                entityType = EntityType.NAMESPACE,
                entityId = nsId.toString(),
                action = Action.READ,
            )
        if (!granted) throw AccessDeniedException("Cannot read prompts in namespace $nsId")

        return promptService
            .findEffective(nsId, currentUser.id)
            .let { prompts ->
                if (request.agentConfigId != null) {
                    prompts.filter { it.agentConfigId == request.agentConfigId }
                } else {
                    prompts
                }
            }.map(::toDto)
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
                "`body.userId` must equal the authenticated user\'s id when supplied. " +
                "A `namespaceId` that does not exist returns 404.",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: PromptDto,
    ): PromptDto {
        val currentUser = userService.getCurrentUser()
        val me = currentUser.id

        // Phase 1 — validation
        if (resource.userId != null && resource.userId != me) {
            throw BadRequestException("userId in body must match authenticated user or be omitted")
        }
        if (resource.content.any { it.isBlank() }) {
            throw BadRequestException("content elements must not be blank")
        }

        // Phase 2 — scope determination
        val resolvedNs: UUID? = resource.namespaceId
        val resolvedUser: UUID? = if (resource.userId != null) me else null
        val isPlatform = resolvedNs == null && resolvedUser == null

        // Phase 3 — per-scope authorization
        when {
            isPlatform -> {
                if (!currentUser.isAdmin) {
                    throw AccessDeniedException("Platform-level Prompt requires Super Admin")
                }
            }
            resolvedNs != null -> {
                val authzAction = if (resolvedUser != null) Action.READ else Action.WRITE
                val granted =
                    permissionService.hasPermission(
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
            // user-global: isAuthenticated() from @PreAuthorize is sufficient
        }

        // Phase 4 — namespace existence check (deferred after authz)
        if (resolvedNs != null && namespaceService.findById(resolvedNs) == null) {
            throw ResourceNotFoundException("Namespace not found: $resolvedNs")
        }

        val target =
            Prompt(
                metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
                namespaceId = resolvedNs,
                userId = resolvedUser,
                agentConfigId = resource.agentConfigId,
                name = resource.name,
                description = resource.description,
                content = resource.content,
                parameters = resource.parameters.map { it.toDomain() },
                externalMetadata = resource.externalMetadata,
            )
        return toDto(promptService.create(target))
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'Prompt', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: PromptDto,
    ): PromptDto {
        val existing =
            promptService.findById(id)
                ?: throw ResourceNotFoundException("Prompt not found: $id")
        return toDto(promptService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'Prompt', 'DELETE')")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) {
        promptService.findById(id)
            ?: throw ResourceNotFoundException("Prompt not found: $id")
        crud.delete(id)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // ExternalId resolution helpers
    // -------------------------------------------------------------------------

    /**
     * Resolves a namespace UUID from either a direct UUID or an externalId.
     * Exactly one must be provided.
     */
    private fun resolveNamespaceId(
        id: UUID?,
        externalId: String?,
    ): UUID {
        if (id != null && externalId != null) {
            throw BadRequestException("Provide namespaceId or namespaceExternalId, not both")
        }
        return id
            ?: externalId?.let {
                namespaceService.findByExternalId(it)?.metadata?.id
                    ?: throw ResourceNotFoundException("Namespace not found for externalId: $it")
            }
            ?: throw BadRequestException("namespaceId or namespaceExternalId is required")
    }

    /**
     * Resolves a namespace UUID from either a direct UUID or an externalId.
     * Both can be null (for platform-level scope).
     */
    private fun resolveOptionalNamespaceId(
        id: UUID?,
        externalId: String?,
    ): UUID? {
        if (id != null && externalId != null) {
            throw BadRequestException("Provide namespaceId or namespaceExternalId, not both")
        }
        return id
            ?: externalId?.let {
                namespaceService.findByExternalId(it)?.metadata?.id
                    ?: throw ResourceNotFoundException("Namespace not found for externalId: $it")
            }
    }

    /**
     * Resolves a user UUID from either a direct UUID or an externalId.
     * Exactly one must be provided.
     */
    private fun resolveUserId(
        id: UUID?,
        externalId: String?,
    ): UUID {
        if (id != null && externalId != null) {
            throw BadRequestException("Provide userId or userExternalId, not both")
        }
        return id
            ?: externalId?.let {
                userService.findByExternalId(it)?.metadata?.id
                    ?: throw ResourceNotFoundException("User not found for externalId: $it")
            }
            ?: throw BadRequestException("userId or userExternalId is required")
    }

    private fun toDomainForUpdate(
        resource: PromptDto,
        existing: Prompt,
    ): Prompt =
        existing.copy(
            // Immutable: namespaceId, userId, agentConfigId
            name = resource.name,
            description = resource.description,
            content = resource.content,
            parameters = resource.parameters.map { it.toDomain() },
            externalMetadata = resource.externalMetadata,
        )

    private fun PromptParameterDto.toDomain(): PromptParameter =
        PromptParameter(
            name = name,
            description = description,
            defaultValue = defaultValue,
        )

    companion object : KLogging()
}

internal fun toDto(entity: Prompt): PromptDto =
    PromptDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        userId = entity.userId,
        agentConfigId = entity.agentConfigId,
        name = entity.name,
        description = entity.description,
        content = entity.content,
        parameters =
            entity.parameters.map { p ->
                PromptParameterDto(
                    name = p.name,
                    description = p.description,
                    defaultValue = p.defaultValue,
                )
            },
        externalMetadata = entity.externalMetadata,
        createdBy = entity.metadata.createdBy,
        createdOn = entity.metadata.created,
        updatedBy = entity.metadata.modifiedBy,
        updatedOn = entity.metadata.modified,
    )

internal fun toDomain(resource: PromptDto): Prompt =
    Prompt(
        metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
        namespaceId = resource.namespaceId,
        userId = resource.userId,
        agentConfigId = resource.agentConfigId,
        name = resource.name,
        description = resource.description,
        content = resource.content,
        parameters =
            resource.parameters.map { p ->
                PromptParameter(
                    name = p.name,
                    description = p.description,
                    defaultValue = p.defaultValue,
                )
            },
        externalMetadata = resource.externalMetadata,
    )
