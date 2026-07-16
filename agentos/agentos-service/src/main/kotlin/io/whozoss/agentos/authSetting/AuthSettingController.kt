package io.whozoss.agentos.authSetting

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.entity.ScopeParams
import io.whozoss.agentos.entity.ScopedOwnershipCrudDelegate
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.authSetting.AuthSetting
import io.whozoss.agentos.sdk.authSetting.AuthType
import io.whozoss.agentos.sdk.authSetting.ApiKeyAuthSetting
import io.whozoss.agentos.sdk.authSetting.BasicAuthAuthSetting
import io.whozoss.agentos.sdk.authSetting.BearerTokenAuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthCustomAuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthDiscoverableAuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthMcpDiscoverableAuthSetting
import io.whozoss.agentos.sdk.authSetting.OAuthRegisteredAuthSetting
import io.whozoss.agentos.sdk.authSetting.authSettingFromDataMap
import io.whozoss.agentos.sdk.authSetting.toDataMap
import io.whozoss.agentos.sdk.authSetting.toSensitiveKeys
import io.whozoss.agentos.sdk.api.authSetting.AuthSettingApi
import io.whozoss.agentos.sdk.api.authSetting.AuthSettingDto
import io.whozoss.agentos.sdk.api.authSetting.ApiKeyAuthSettingDto
import io.whozoss.agentos.sdk.api.authSetting.BasicAuthAuthSettingDto
import io.whozoss.agentos.sdk.api.authSetting.BearerTokenAuthSettingDto
import io.whozoss.agentos.sdk.api.authSetting.OAuthCustomAuthSettingDto
import io.whozoss.agentos.sdk.api.authSetting.OAuthDiscoverableAuthSettingDto
import io.whozoss.agentos.sdk.api.authSetting.OAuthMcpDiscoverableAuthSettingDto
import io.whozoss.agentos.sdk.api.authSetting.OAuthRegisteredAuthSettingDto
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
 * REST API for [AuthSetting] entities. Implements [AuthSettingApi] so external consumers
 * can declare a Feign client against the SDK interface.
 *
 * Three-scope model (NS-shared, user×namespace, user-global) plus a platform scope
 * (both null) for super-admins. Standard CRUD and ownership-aware batch fetch are
 * delegated to [crud]. The [create] and [list] overrides carry scope-specific logic.
 *
 * **data masking**: [toDto] uses [maskDataMapSelective] to mask only the sensitive keys
 * for each auth type (via [AuthSetting.toSensitiveKeys]). Non-sensitive properties such
 * as `clientId` and `discoveryUrl` are returned in plain text.
 *
 * [resolveDataMap] handles the per-key PUT semantics: masked sentinel / null → preserve,
 * blank → clear, non-blank → replace.
 */
@RestController
@RequestMapping(
    "/api/auth-settings",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AuthSettingController(
    private val authSettingService: AuthSettingService,
    private val namespaceService: NamespaceService,
    private val userService: UserService,
    private val permissionService: PermissionService,
) : AuthSettingApi {
    private val crud =
        ScopedOwnershipCrudDelegate<AuthSettingDto>(
            entityLabel = "AuthSetting",
            userService = userService,
            namespaceService = namespaceService,
            permissionService = permissionService,
            ownerOf = { (it as AuthSetting).userId },
            userIdOf = { it.userId },
            namespaceIdOf = { it.namespaceId },
            buildEntity = { resource, resolvedNs, resolvedUser ->
                authSettingFromDataMap(
                    authType = resource.authType!!,
                    data = dtoToDataMap(resource),
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = resolvedNs,
                    userId = resolvedUser,
                    name = resource.name,
                    description = resource.description,
                )
            },
            crud =
                EntityCrudDelegate(
                    service = authSettingService,
                    userService = userService,
                    permissions = permissionService,
                    entityType = EntityType.AUTH_SETTING,
                    toResource = { toDto(it as AuthSetting) },
                ),
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AuthSetting', 'READ')")
    @HideOnAccessDenied
    override fun getById(
        @PathVariable id: UUID,
    ): AuthSettingDto = crud.getById(id)

    /**
     * POST /by-ids — overridden to honour the ownership branch.
     *
     * User-owned overlays would be invisible through the batch endpoint even though
     * `GET /{id}` returns them via the evaluator's ownership branch. This override
     * unions membership-visible IDs with rows the caller owns.
     */
    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(
        @RequestBody request: SdkGetByIdsRequest,
    ): List<AuthSettingDto> = crud.getByIds(GetByIdsRequest(request.ids, request.withRemoved))

    @Operation(
        summary = "List AuthSettings by scope",
        description =
            "Scope is inferred from the query params:\n\n" +
                "| query                            | mode             | required permission                            |\n" +
                "|----------------------------------|------------------|------------------------------------------------|\n" +
                "| (no params)                      | platform         | authenticated                                  |\n" +
                "| `?namespaceId=<uuid>`            | NS-shared        | READ on the namespace (empty list if missing)  |\n" +
                "| `?namespaceId=<uuid>&userId=me`  | user \u00d7 namespace | authenticated                                  |\n" +
                "| `?namespaceId=none&userId=me`    | user-global      | authenticated                                  |\n" +
                "| `?userId=me` (no namespace)      | all caller's     | authenticated                                  |\n\n" +
                "`userId` accepts ONLY the literal sentinel `me` \u2014 a UUID returns 400. " +
                "`namespaceId=none` is the sentinel for `namespaceId IS NULL`.",
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    override fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
    ): List<AuthSettingDto> {
        val resolvedNs = ScopeParams.parseNamespaceParam(namespaceId)
        val me = userService.getCurrentUser().id
        ScopeParams.validateUserParam(userId)
        return authSettingService
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
    @Operation(
        summary = "Create an AuthSetting",
        description =
            "Scope is inferred implicitly from the body's `(namespaceId, userId)` pair:\n\n" +
                "| body.namespaceId | body.userId        | scope         | required permission                  |\n" +
                "|------------------|--------------------|---------------|--------------------------------------|\n" +
                "| null             | null               | platform      | super-admin only                     |\n" +
                "| present          | null               | NS-shared     | WRITE on the namespace               |\n" +
                "| null             | <currentUser.id>   | user-global   | authenticated only                   |\n" +
                "| present          | <currentUser.id>   | user\u00d7namespace| READ on the namespace                |\n\n" +
                "`body.userId` (when supplied) MUST equal the authenticated user's id \u2014 sending a different " +
                "user-id is rejected with 400 (mass-assignment guard). A `namespaceId` " +
                "that does not exist returns 404.",
    )
    override fun create(
        @Valid @RequestBody resource: AuthSettingDto,
    ): AuthSettingDto {
        // Platform scope (both null) — super-admin only; bypass ScopedOwnershipCrudDelegate
        // which requires at least one of namespaceId/userId to be non-null.
        if (resource.namespaceId == null && resource.userId == null) {
            val me = userService.getCurrentUser()
            if (!me.isAdmin) {
                throw AccessDeniedException("Platform-level AuthSetting requires Super Admin")
            }
            val entity =
                authSettingFromDataMap(
                    authType = resource.authType!!,
                    data = dtoToDataMap(resource),
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    userId = null,
                    name = resource.name,
                    description = resource.description,
                )
            return toDto(authSettingService.create(entity))
        }

        // Non-platform scopes — delegate handles userId guard + namespace authz.
        return crud.create(resource)
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AuthSetting', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AuthSettingDto,
    ): AuthSettingDto {
        val existing =
            authSettingService.findById(id)
                ?: throw ResourceNotFoundException("AuthSetting not found: $id")
        val resolvedData = resolveDataMap(dtoToDataMap(resource), existing.toDataMap())
        return toDto(
            authSettingService.update(
                authSettingFromDataMap(
                    authType = existing.authType, // immutable post-create
                    data = resolvedData,
                    metadata = existing.metadata,
                    namespaceId = existing.namespaceId,
                    userId = existing.userId,
                    name = resource.name,
                    description = resource.description,
                ),
            ),
        )
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AuthSetting', 'DELETE')")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun delete(
        @PathVariable id: UUID,
    ) = crud.delete(id)

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Per-key data map resolution on PUT (NFR-SEC-1):
     *
     * For each key in the incoming map:
     * - masked sentinel (contains "****") → preserve the existing value
     * - blank string → clear (remove the key)
     * - non-blank, non-masked → replace
     *
     * Keys present in the existing map but absent from the incoming map are preserved
     * (the client did not intend to touch them).
     */
    private fun resolveDataMap(
        incoming: Map<String, String>,
        current: Map<String, String>,
    ): Map<String, String> {
        val result = current.toMutableMap()
        for ((key, value) in incoming) {
            when {
                isDataValueMasked(value) -> { /* preserve — no-op */ }
                value.isBlank() -> result.remove(key)
                else -> result[key] = value
            }
        }
        return result
    }

    companion object : KLogging()
}

/**
 * Extract the typed properties of an [AuthSettingDto] subtype into a flat string map.
 *
 * Null properties (absent or masked fields not yet supplied by the client) are omitted
 * from the map so that [AuthSettingController.resolveDataMap] treats them as
 * "preserve existing" rather than "clear".
 */
internal fun dtoToDataMap(dto: AuthSettingDto): Map<String, String> =
    when (dto) {
        is ApiKeyAuthSettingDto -> buildMap {
            dto.apiKey?.let { put("apiKey", it) }
        }
        is BearerTokenAuthSettingDto -> buildMap {
            dto.token?.let { put("token", it) }
        }
        is BasicAuthAuthSettingDto -> buildMap {
            dto.username?.let { put("username", it) }
            dto.password?.let { put("password", it) }
        }
        is OAuthDiscoverableAuthSettingDto -> buildMap {
            dto.discoveryUrl?.let { put("discoveryUrl", it) }
            dto.clientId?.let { put("clientId", it) }
            dto.clientSecret?.let { put("clientSecret", it) }
            dto.scopes?.let { put("scopes", it) }
        }
        is OAuthRegisteredAuthSettingDto -> buildMap {
            dto.clientId?.let { put("clientId", it) }
            dto.clientSecret?.let { put("clientSecret", it) }
            dto.authorizationUrl?.let { put("authorizationUrl", it) }
            dto.tokenUrl?.let { put("tokenUrl", it) }
            dto.scopes?.let { put("scopes", it) }
        }
        is OAuthCustomAuthSettingDto -> buildMap {
            dto.clientId?.let { put("clientId", it) }
            dto.clientSecret?.let { put("clientSecret", it) }
            dto.authorizationUrl?.let { put("authorizationUrl", it) }
            dto.tokenUrl?.let { put("tokenUrl", it) }
            dto.scopes?.let { put("scopes", it) }
        }
        is OAuthMcpDiscoverableAuthSettingDto -> buildMap {
            dto.resourceUrl?.let { put("resourceUrl", it) }
            dto.clientId?.let { put("clientId", it) }
            dto.clientSecret?.let { put("clientSecret", it) }
            dto.scopes?.let { put("scopes", it) }
        }
    }

/**
 * Map an [AuthSetting] to its corresponding [AuthSettingDto] subtype.
 *
 * Sensitive properties (per [AuthSetting.toSensitiveKeys]) are masked with
 * [maskSensitiveValue]; non-sensitive properties (e.g. `clientId`, `discoveryUrl`,
 * `username`) are returned in plain text so the client can identify the setting.
 */
internal fun toDto(entity: AuthSetting): AuthSettingDto {
    val data = entity.toDataMap()
    val sensitiveKeys = entity.toSensitiveKeys()
    val maskedData = maskDataMapSelective(data, sensitiveKeys)
    return when (entity) {
        is ApiKeyAuthSetting ->
            ApiKeyAuthSettingDto(
                id = entity.metadata.id,
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                name = entity.name,
                description = entity.description,
                authType = entity.authType,
                apiKey = maskedData?.get("apiKey"),
            )
        is BearerTokenAuthSetting ->
            BearerTokenAuthSettingDto(
                id = entity.metadata.id,
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                name = entity.name,
                description = entity.description,
                authType = entity.authType,
                token = maskedData?.get("token"),
            )
        is BasicAuthAuthSetting ->
            BasicAuthAuthSettingDto(
                id = entity.metadata.id,
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                name = entity.name,
                description = entity.description,
                authType = entity.authType,
                username = maskedData?.get("username"),
                password = maskedData?.get("password"),
            )
        is OAuthDiscoverableAuthSetting ->
            OAuthDiscoverableAuthSettingDto(
                id = entity.metadata.id,
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                name = entity.name,
                description = entity.description,
                authType = entity.authType,
                discoveryUrl = maskedData?.get("discoveryUrl"),
                clientId = maskedData?.get("clientId"),
                clientSecret = maskedData?.get("clientSecret"),
                scopes = maskedData?.get("scopes"),
            )
        is OAuthRegisteredAuthSetting ->
            OAuthRegisteredAuthSettingDto(
                id = entity.metadata.id,
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                name = entity.name,
                description = entity.description,
                authType = entity.authType,
                clientId = maskedData?.get("clientId"),
                clientSecret = maskedData?.get("clientSecret"),
                authorizationUrl = maskedData?.get("authorizationUrl"),
                tokenUrl = maskedData?.get("tokenUrl"),
                scopes = maskedData?.get("scopes"),
            )
        is OAuthCustomAuthSetting ->
            OAuthCustomAuthSettingDto(
                id = entity.metadata.id,
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                name = entity.name,
                description = entity.description,
                authType = entity.authType,
                clientId = maskedData?.get("clientId"),
                clientSecret = maskedData?.get("clientSecret"),
                authorizationUrl = maskedData?.get("authorizationUrl"),
                tokenUrl = maskedData?.get("tokenUrl"),
                scopes = maskedData?.get("scopes"),
            )
        is OAuthMcpDiscoverableAuthSetting ->
            OAuthMcpDiscoverableAuthSettingDto(
                id = entity.metadata.id,
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                name = entity.name,
                description = entity.description,
                authType = entity.authType,
                resourceUrl = maskedData?.get("resourceUrl"),
                clientId = maskedData?.get("clientId"),
                clientSecret = maskedData?.get("clientSecret"),
                scopes = maskedData?.get("scopes"),
            )
    }
}
