package io.whozoss.agentos.aiProvider

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.entity.ScopeParams
import io.whozoss.agentos.entity.ScopedOwnershipCrudDelegate
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.api.aiProvider.AiProviderApi
import io.whozoss.agentos.sdk.api.aiProvider.AiProviderDto
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
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest as SdkGetByIdsRequest

/**
 * REST API for [AiProvider] entities. Implements [AiProviderApi] so external consumers
 * can declare a Feign client against the SDK interface.
 *
 * Three-scope model (NS-shared, user×namespace, user-global) plus a platform scope
 * (both null) for super-admins. Standard CRUD and ownership-aware batch fetch are
 * delegated to [crud]. The [create] and [list] overrides carry scope-specific logic.
 *
 * **apiKey masking**: the file-level [maskApiKey] function in [ApiKeyMasking] is used
 * in [toDto] for all read paths. [resolveApiKey] handles the 4-way PUT semantics:
 * masked sentinel / null → preserve, blank → clear, non-blank → replace.
 */
@RestController
@RequestMapping(
    "/api/ai-providers",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AiProviderController(
    private val aiProviderService: AiProviderService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : AiProviderApi {

    private val crud = ScopedOwnershipCrudDelegate<AiProviderDto>(
        entityLabel = "AiProvider",
        userService = userService,
        namespaceService = namespaceService,
        permissionService = permissionService,
        ownerOf = { (it as AiProvider).userId },
        userIdOf = { it.userId },
        namespaceIdOf = { it.namespaceId },
        buildEntity = { resource, resolvedNs, resolvedUser ->
            AiProvider(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = resolvedNs,
                userId = resolvedUser,
                name = resource.name,
                description = resource.description,
                apiType = resource.apiType!!,
                baseUrl = resource.baseUrl,
                apiKey = resource.apiKey,
                headers = resource.headers ?: emptyMap(),
            )
        },
        crud = EntityCrudDelegate(
            service = aiProviderService,
            userService = userService,
            permissions = permissionService,
            entityType = EntityType.AI_PROVIDER,
            toResource = { toDto(it as AiProvider) },
        ),
    )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiProvider', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): AiProviderDto = crud.getById(id)

    /**
     * POST /by-ids — overridden to honour the ownership branch.
     *
     * The base implementation only consults permission-graph edges. Owner-only rows
     * (a user’s overlays) would be invisible through the batch endpoint even though
     * `GET /{id}` returns them via the evaluator’s ownership branch. This override
     * unions membership-visible IDs with rows the caller owns — bounded by input size.
     */
    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<AiProviderDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @Operation(summary = "List AiProviders by scope")
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    override fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
    ): List<AiProviderDto> {
        val resolvedNs = ScopeParams.parseNamespaceParam(namespaceId)
        val me = userService.getCurrentUser().id
        ScopeParams.validateUserParam(userId)
        return aiProviderService
            .findFiltered(
                namespaceId = resolvedNs,
                namespaceIsNone = namespaceId?.equals(ScopeParams.NONE, ignoreCase = true) == true,
                callerId = me,
                userRequested = userId != null,
            ).map(::toDto)
    }

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: AiProviderDto,
    ): AiProviderDto {
        // Platform scope (both null) — super-admin only; bypass ScopedOwnershipCrudDelegate
        // which requires at least one of namespaceId/userId to be non-null.
        if (resource.namespaceId == null && resource.userId == null) {
            val me = userService.getCurrentUser()
            if (!me.isAdmin) {
                throw AccessDeniedException("Platform-level AiProvider requires Super Admin")
            }
            val entity = AiProvider(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = null,
                userId = null,
                name = resource.name,
                description = resource.description,
                apiType = resource.apiType!!,
                baseUrl = resource.baseUrl,
                apiKey = resource.apiKey,
                headers = resource.headers ?: emptyMap(),
            )
            return toDto(aiProviderService.create(entity))
        }

        // Non-platform scopes — delegate handles userId guard + namespace authz.
        return crud.create(resource)
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AiProvider', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AiProviderDto,
    ): AiProviderDto {
        val existing = aiProviderService.findById(id)
            ?: throw ResourceNotFoundException("AiProvider not found: $id")
        return toDto(
            aiProviderService.update(
                existing.copy(
                    name = resource.name,
                    description = resource.description,
                    apiType = existing.apiType, // immutable post-create
                    baseUrl = resource.baseUrl,
                    apiKey = resolveApiKey(resource.apiKey, existing.apiKey),
                    headers = resource.headers ?: emptyMap(),
                ),
            ),
        )
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiProvider', 'DELETE')")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Four-way apiKey semantics on PUT (FR25, NFR-SEC-1):
     * - masked sentinel (“****” pattern) → preserve persisted credential
     * - null → preserve (field omitted by client)
     * - blank string (“”) → clear the credential
     * - non-blank → replace
     */
    private fun resolveApiKey(incoming: String?, current: String?): String? =
        when {
            isMasked(incoming) -> current
            incoming == null -> current
            incoming.isBlank() -> null
            else -> incoming
        }

    companion object : KLogging()
}

internal fun toDto(entity: AiProvider) =
    AiProviderDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        userId = entity.userId,
        name = entity.name,
        description = entity.description,
        apiType = entity.apiType,
        baseUrl = entity.baseUrl,
        apiKey = maskApiKey(entity.apiKey),
        headers = entity.headers,
    )
