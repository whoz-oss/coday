package io.whozoss.agentos.authSetting

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.auth.OAuthFlowService
import io.whozoss.agentos.credential.CredentialService
import io.whozoss.agentos.entity.EntityCrudDelegate
import io.whozoss.agentos.entity.GetByIdsRequest
import io.whozoss.agentos.entity.ScopeParams
import io.whozoss.agentos.entity.ScopedOwnershipCrudDelegate
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ConfigNotFoundException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.permissions.ScopedAccessPolicy
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
    private val scopedAccessPolicy: ScopedAccessPolicy,
    private val credentialService: CredentialService,
    private val oAuthFlowService: OAuthFlowService,
) : AuthSettingApi {
    private val crud =
        ScopedOwnershipCrudDelegate<AuthSettingDto>(
            entityLabel = "AuthSetting",
            userService = userService,
            namespaceService = namespaceService,
            scopedAccessPolicy = scopedAccessPolicy,
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
                "| (no params)                      | all caller's     | authenticated                                  |\n" +
                "| `?namespaceId=none` (no userId)  | platform         | authenticated                                  |\n" +
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

    /**
     * DELETE /{id}/credential — forget the caller's own stored connection to an AuthSetting.
     *
     * Removes the per-user `Credential` row (OAuth tokens and, for dynamically registered MCP
     * servers, the registered client), so the next use of an integration bound to this setting
     * runs a fresh authorization. Only ever touches the caller's credential, whatever the scope
     * of the setting: READ on the setting is enough because the row being deleted is personal.
     * Idempotent: answers 204 whether or not a credential existed.
     */
    @DeleteMapping("/{id}/credential")
    @PreAuthorize("hasPermission(#id, 'AuthSetting', 'READ')")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
        summary = "Reset my connection to an AuthSetting",
        description =
            "Deletes the caller's own stored credential for this AuthSetting (OAuth tokens), so the " +
                "next use of an integration bound to it asks for a new authorization. Other users' " +
                "credentials are never touched. Requires READ on the AuthSetting; idempotent (204 even " +
                "when the caller had no credential).",
    )
    fun resetMyCredential(
        @PathVariable id: UUID,
        @RequestParam(required = false) namespaceId: UUID? = null,
    ) {
        val me = userService.getCurrentUser().id
        val target = credentialTarget(id, namespaceId, me)
        val deleted = credentialService.delete(me, target.metadata.id)
        logger.info {
            "[AuthSetting] Credential reset for authSetting=${target.metadata.id} " +
                "(requested=$id, user=$me, hadCredential=$deleted)"
        }
    }

    /**
     * GET /{id}/connection — whether the caller holds a usable connection to an OAuth AuthSetting.
     *
     * Reports a state, never a token. [AuthSettingConnection.connectedAt] moves forward on every new
     * authorization, which lets a client tell a reconnection that completed from one that did not.
     */
    @GetMapping("/{id}/connection")
    @PreAuthorize("hasPermission(#id, 'AuthSetting', 'READ')")
    @HideOnAccessDenied
    @Operation(
        summary = "Get my connection state for an AuthSetting",
        description =
            "Tells whether the caller holds a stored connection that can still serve a run. Pass the " +
                "namespaceId the setting is used from: a connection belongs to the layer the runtime resolves " +
                "the setting name to, which depends on the namespace.",
    )
    fun getConnection(
        @PathVariable id: UUID,
        @RequestParam(required = false) namespaceId: UUID? = null,
    ): AuthSettingConnection {
        val me = userService.getCurrentUser().id
        val target = credentialTarget(id, namespaceId, me)
        val credential = oAuthFlowService.findUsableCredential(me, target.metadata.id)
        return AuthSettingConnection(connected = credential != null, connectedAt = credential?.metadata?.modified)
    }

    /**
     * POST /{id}/connect — start an authorization for the caller, outside any conversation.
     *
     * Returns the URL to open in a popup. The flow then completes on its own through `/api/oauth/callback`
     * and replaces the stored connection; read [getConnection] to see it land. Always authorizes the caller's
     * own account: nobody can be connected on behalf of someone else.
     */
    @PostMapping("/{id}/connect")
    @PreAuthorize("hasPermission(#id, 'AuthSetting', 'READ')")
    @HideOnAccessDenied
    @Operation(
        summary = "Connect my account to an AuthSetting",
        description =
            "Starts an OAuth authorization for the caller and returns the URL to open. Only OAuth " +
                "settings can be connected (400 otherwise); 422 when the instance has no OAuth redirect URI " +
                "configured or the authorization could not start. A new authorization replaces an existing " +
                "connection only once it completes.",
    )
    suspend fun connect(
        @PathVariable id: UUID,
        @RequestParam(required = false) namespaceId: UUID? = null,
    ): AuthSettingConnectResponse {
        val me = userService.getCurrentUser().id
        val target = credentialTarget(id, namespaceId, me)
        if (target.authType !in OAUTH_TYPES) {
            throw BadRequestException("AuthSetting '${target.name}' is not an OAuth setting: there is nothing to connect")
        }
        if (!oAuthFlowService.isInteractiveAvailable()) {
            throw UnprocessableEntityException(
                "OAuth authorization is not configured on this instance (AGENTOS_OAUTH_REDIRECT_URI is not set)",
            )
        }
        val authorizationUrl =
            oAuthFlowService.startInteractiveConnection(me, target)
                ?: throw UnprocessableEntityException(
                    "The authorization could not be started for '${target.name}': check the OAuth endpoints of the setting",
                )
        logger.info { "[AuthSetting] Connection started for authSetting=${target.metadata.id} (requested=$id, user=$me)" }
        return AuthSettingConnectResponse(authorizationUrl)
    }

    /**
     * The AuthSetting whose credential the caller means.
     *
     * A credential is stored under the id of the **lowest layer** defining the name in the caller's context
     * (the merge keeps the base metadata), not under the id of the row the user clicked: a personal override
     * of a shared setting shares the credential of the shared one. Without [namespaceId] the row is taken as
     * is. With it, the name is resolved the way a run resolves it, after checking the caller may read that
     * namespace: resolving through one they cannot read would let them use its shared client credentials.
     */
    private fun credentialTarget(
        id: UUID,
        namespaceId: UUID?,
        me: UUID,
    ): AuthSetting {
        val clicked =
            authSettingService.findById(id)
                ?: throw ResourceNotFoundException("AuthSetting not found: $id")
        if (namespaceId == null) return clicked
        if (!permissionService.hasPermission(me.toString(), EntityType.NAMESPACE, namespaceId.toString(), Action.READ)) {
            throw AccessDeniedException("No read permission on namespace $namespaceId")
        }
        return try {
            authSettingService.resolveAuthSetting(namespaceId, me, clicked.name)
        } catch (e: ConfigNotFoundException) {
            throw ResourceNotFoundException("AuthSetting not found: $id")
        }
    }

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

                value.isBlank() -> {
                    result.remove(key)
                }

                else -> {
                    result[key] = value
                }
            }
        }
        return result
    }

    companion object : KLogging() {
        /** The settings a user can authorize interactively: their credential is a stored OAuth connection. */
        private val OAUTH_TYPES =
            setOf(
                AuthType.OAUTH_DISCOVERABLE,
                AuthType.OAUTH_REGISTERED,
                AuthType.OAUTH_CUSTOM,
                AuthType.OAUTH_MCP_DISCOVERABLE,
            )
    }
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
        is ApiKeyAuthSettingDto -> {
            buildMap {
                dto.apiKey?.let { put("apiKey", it) }
            }
        }

        is BearerTokenAuthSettingDto -> {
            buildMap {
                dto.token?.let { put("token", it) }
            }
        }

        is BasicAuthAuthSettingDto -> {
            buildMap {
                dto.username?.let { put("username", it) }
                dto.password?.let { put("password", it) }
            }
        }

        is OAuthDiscoverableAuthSettingDto -> {
            buildMap {
                dto.discoveryUrl?.let { put("discoveryUrl", it) }
                dto.clientId?.let { put("clientId", it) }
                dto.clientSecret?.let { put("clientSecret", it) }
                dto.scopes?.let { put("scopes", it) }
            }
        }

        is OAuthRegisteredAuthSettingDto -> {
            buildMap {
                dto.clientId?.let { put("clientId", it) }
                dto.clientSecret?.let { put("clientSecret", it) }
                dto.authorizationUrl?.let { put("authorizationUrl", it) }
                dto.tokenUrl?.let { put("tokenUrl", it) }
                dto.scopes?.let { put("scopes", it) }
            }
        }

        is OAuthCustomAuthSettingDto -> {
            buildMap {
                dto.clientId?.let { put("clientId", it) }
                dto.clientSecret?.let { put("clientSecret", it) }
                dto.authorizationUrl?.let { put("authorizationUrl", it) }
                dto.tokenUrl?.let { put("tokenUrl", it) }
                dto.scopes?.let { put("scopes", it) }
            }
        }

        is OAuthMcpDiscoverableAuthSettingDto -> {
            buildMap {
                dto.resourceUrl?.let { put("resourceUrl", it) }
                dto.clientId?.let { put("clientId", it) }
                dto.clientSecret?.let { put("clientSecret", it) }
                dto.scopes?.let { put("scopes", it) }
            }
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
        is ApiKeyAuthSetting -> {
            ApiKeyAuthSettingDto(
                id = entity.metadata.id,
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                name = entity.name,
                description = entity.description,
                authType = entity.authType,
                apiKey = maskedData?.get("apiKey"),
            )
        }

        is BearerTokenAuthSetting -> {
            BearerTokenAuthSettingDto(
                id = entity.metadata.id,
                namespaceId = entity.namespaceId,
                userId = entity.userId,
                name = entity.name,
                description = entity.description,
                authType = entity.authType,
                token = maskedData?.get("token"),
            )
        }

        is BasicAuthAuthSetting -> {
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
        }

        is OAuthDiscoverableAuthSetting -> {
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
        }

        is OAuthRegisteredAuthSetting -> {
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
        }

        is OAuthCustomAuthSetting -> {
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
        }

        is OAuthMcpDiscoverableAuthSetting -> {
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
}
