package io.whozoss.agentos.integrationConfig

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.entity.ScopeParams
import io.whozoss.agentos.entity.ScopedOwnershipCrudDelegate
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigApi
import io.whozoss.agentos.sdk.api.integrationConfig.IntegrationConfigDto
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
 * Unified REST API for [IntegrationConfig] entities — covers the **four scopes**
 * (platform, NS-shared, user × namespace, user-global) under a single set of routes.
 *
 * Standard read and delete operations are delegated to [scopedOwnershipCrudDelegate] (a
 * [ScopedOwnershipCrudDelegate] wrapping an [EntityCrudDelegate]). [create], [update],
 * [list], and [listByParent] are explicit overrides because they carry logic that is
 * specific to this controller (platform-scope guard, immutable-field preservation,
 * custom filtering).
 *
 * **Scope dispatch on `POST`** — inferred from `(body.namespaceId, body.userId)`:
 *  - both null → platform scope (Super Admin only)
 *  - `(ns, null)` → NS-shared (WRITE on namespace required)
 *  - `(null, user)` → user-global (authenticated only)
 *  - `(ns, user)` → user × namespace (READ on namespace required)
 *
 * **Platform guard on `PUT` / `DELETE`** — a platform-scoped entity
 * (`namespaceId == null && userId == null`) may only be mutated by a Super Admin.
 *
 * **Mass-assignment guards** — on `PUT`, `id`, `namespaceId`, `userId`, and
 * `integrationType` are preserved from the persisted row. `integrationType` is
 * immutable post-create (Decision 18 / AC11b).
 */
@RestController
@RequestMapping(
    "/api/integration-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationConfigController(
    private val integrationConfigService: IntegrationConfigService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : IntegrationConfigApi {
    private val scopedOwnershipCrudDelegate =
        ScopedOwnershipCrudDelegate(
            entityLabel = "IntegrationConfig",
            userService = userService,
            namespaceService = namespaceService,
            permissionService = permissionService,
            ownerOf = { (it as IntegrationConfig).userId },
            userIdOf = { it.userId },
            namespaceIdOf = { it.namespaceId },
            buildEntity = { resource, resolvedNs, resolvedUser ->
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = resolvedNs,
                    userId = resolvedUser,
                    name = resource.name,
                    integrationType = resource.integrationType,
                    description = resource.description,
                    parameters = resource.parameters,
                    authSettingName = resource.authSettingName,
                )
            },
            crud =
                EntityCrudDelegate(
                    service = integrationConfigService,
                    userService = userService,
                    permissions = permissionService,
                    entityType = EntityType.INTEGRATION_CONFIG,
                    toResource = { toDto(it as IntegrationConfig) },
                    // toDomain omitted: ScopedOwnershipCrudDelegate always calls createEntity;
                    // crud.create(resource) is never invoked directly.
                ),
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): IntegrationConfigDto = scopedOwnershipCrudDelegate.getById(id)

    @PostMapping("/by-ids", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<IntegrationConfigDto> = scopedOwnershipCrudDelegate.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    /**
     * Hard-break stub for the legacy `GET /by-parentId/{parentId}`. Hidden from
     * the OpenAPI spec so the SDK no longer surfaces `listByParentIntegrationConfig`.
     */
    @Hidden
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("isAuthenticated()")
    override fun listByParent(
        @PathVariable parentId: UUID,
    ): List<IntegrationConfigDto> =
        throw ResourceNotFoundException(
            "Endpoint removed; use GET /api/integration-configs?namespaceId=$parentId instead",
        )

    @Operation(
        summary = "List IntegrationConfigs by scope",
        description =
            "Scope is inferred from the query params :\n\n" +
                "| query                            | mode             | required permission                            |\n" +
                "|----------------------------------|------------------|------------------------------------------------|\n" +
                "| (no params)                      | platform         | authenticated                                  |\n" +
                "| `?namespaceId=<uuid>`            | NS-shared        | READ on the namespace (empty list if missing)  |\n" +
                "| `?namespaceId=<uuid>&userId=me`  | user × namespace | authenticated                                  |\n" +
                "| `?namespaceId=none&userId=me`    | user-global      | authenticated                                  |\n" +
                "| `?userId=me` (no namespace)      | all caller's     | authenticated                                  |\n\n" +
                "`userId` accepts ONLY the literal sentinel `me` \u2014 a UUID returns 400. " +
                "`namespaceId=none` is the sentinel for `namespaceId IS NULL`.\n\n" +
                "When called with no params, returns platform-level configs.",
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    override fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
    ): List<IntegrationConfigDto> {
        val currentUser = userService.getCurrentUser()
        ScopeParams.validateUserParam(userId)

        if (namespaceId == null && userId == null) {
            return integrationConfigService.findPlatform().map(::toDto)
        }

        val resolvedNs = ScopeParams.parseNamespaceParam(namespaceId)
        return integrationConfigService
            .findFiltered(
                namespaceId = resolvedNs,
                namespaceIsNone = namespaceId?.equals(ScopeParams.NONE, ignoreCase = true) == true,
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
            ).map(::toDto)
    }

    @Operation(
        summary = "Create an IntegrationConfig",
        description =
            "Scope is inferred implicitly from the body's `(namespaceId, userId)` pair :\n\n" +
                "| body.namespaceId | body.userId        | scope         | required permission                  |\n" +
                "|------------------|--------------------|---------------|--------------------------------------|\n" +
                "| null             | null               | platform      | Super Admin only                     |\n" +
                "| present          | null               | NS-shared     | WRITE on the namespace               |\n" +
                "| null             | <currentUser.id>   | user-global   | authenticated only                   |\n" +
                "| present          | <currentUser.id>   | user×namespace| READ on the namespace                |\n\n" +
                "`body.userId` (when supplied) MUST equal the authenticated user's id. " +
                "A `namespaceId` that does not exist returns 404.",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(
        @Valid @RequestBody resource: IntegrationConfigDto,
    ): IntegrationConfigDto {
        // Platform scope (both null) — Super Admin only; bypass ScopedOwnershipCrudDelegate
        // which requires at least one of namespaceId/userId to be non-null.
        if (resource.namespaceId == null && resource.userId == null) {
            requireSuperAdmin()
            val entity =
                IntegrationConfig(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = null,
                    name = resource.name,
                    integrationType = resource.integrationType,
                    description = resource.description,
                    parameters = resource.parameters,
                    authSettingName = resource.authSettingName,
                )
            return toDto(integrationConfigService.create(entity))
        }

        // Non-platform scopes — delegate handles userId guard + namespace authz.
        return scopedOwnershipCrudDelegate.create(resource)
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: IntegrationConfigDto,
    ): IntegrationConfigDto {
        val existing =
            integrationConfigService.findById(id)
                ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        requireAdminForPlatform(existing.namespaceId, existing.userId)
        return toDto(
            integrationConfigService.update(
                existing.copy(
                    name = resource.name,
                    // integrationType is immutable post-create (Decision 18 / AC11b)
                    integrationType = existing.integrationType,
                    description = resource.description,
                    parameters = resource.parameters,
                    authSettingName = resource.authSettingName,
                ),
            ),
        )
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'DELETE')")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) {
        val existing =
            integrationConfigService.findById(id)
                ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        requireAdminForPlatform(existing.namespaceId, existing.userId)
        scopedOwnershipCrudDelegate.delete(id)
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Throws [AccessDeniedException] when the entity is platform-scoped
     * (`namespaceId == null && userId == null`) and the caller is not a Super Admin.
     * Short-circuits immediately for non-platform entities.
     */
    private fun requireAdminForPlatform(
        namespaceId: UUID?,
        userId: UUID?,
    ) {
        if (namespaceId != null || userId != null) return
        requireSuperAdmin()
    }

    private fun requireSuperAdmin() {
        if (!userService.getCurrentUser().isAdmin) {
            throw AccessDeniedException("Platform-level IntegrationConfig requires Super Admin")
        }
    }

    companion object : KLogging()
}

private fun toDto(entity: IntegrationConfig) =
    IntegrationConfigDto(
        id = entity.metadata.id,
        namespaceId = entity.namespaceId,
        userId = entity.userId,
        name = entity.name,
        integrationType = entity.integrationType,
        description = entity.description,
        parameters = entity.parameters,
        authSettingName = entity.authSettingName,
    )
