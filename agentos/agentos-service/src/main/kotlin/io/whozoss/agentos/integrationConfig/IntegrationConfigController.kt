package io.whozoss.agentos.integrationConfig

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.BadRequestException
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
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
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import kotlin.math.min

/**
 * Unified REST API for [IntegrationConfig] entities — covers the **three scopes**
 * (NS-shared, user × namespace, user-global) under a single set of routes.
 *
 * **Implicit scope dispatch on `POST` (Decision 15)** — the controller infers the
 * scope from the `(body.namespaceId, body.userId)` pair. Phases :
 *  1. Mass-assignment guard : `body.userId`, when non-null, must equal
 *     `auth.principal.userId`. Otherwise → 400.
 *  2. Scope determination :
 *     - both null → 400 ;
 *     - `(ns, null)` → NS-shared ;
 *     - `(null, user)` → user-global ;
 *     - `(ns, user)` → user × namespace.
 *  3. Per-scope authorization (run BEFORE existence so unauthorised callers always
 *     get 403, never an existence-leak 404) : NS-shared → `WRITE` ; user × ns →
 *     `READ` ; user-global → `isAuthenticated()`.
 *  4. Namespace existence check (when `ns != null`) → 404 if dangling. Still required
 *     after authz because the super-admin bypass in `permissionService.hasPermission`
 *     returns `true` for missing ids, which would otherwise let an admin create
 *     dangling FK rows.
 *  5. Domain build is **explicit** : the persisted entity uses the controller-
 *     resolved `(namespaceId, userId)` — never the raw body.
 *
 * **Authorization on read / update / delete** uses `@PreAuthorize("hasPermission(#id, 'IntegrationConfig', ACTION)")` —
 * [io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator] tries the
 * membership / super-admin path first, then falls through to ownership for
 * [EntityType.INTEGRATION_CONFIG].
 *
 * **Existence-hiding** : every authz-protected endpoint (incl. `PUT` / `DELETE`)
 * is annotated [HideOnAccessDenied] so cross-user probes return 404 — indistinguishable
 * from a missing row (Decision 20, breaking change vs. pre-fusion 403 on NS write).
 *
 * **Mass-assignment guards** :
 *  - On `POST`, the persisted `userId` is the authenticated user's id.
 *  - On `PUT`, `id`, `namespaceId`, `userId` and `integrationType` are preserved
 *    from the persisted row. `integrationType` is immutable post-create
 *    (Decision 18 / AC11b) — aligns NS path on the invariant the user controller
 *    already enforced.
 *
 * **`parameters` field semantics on `PUT`** — replaced wholesale by the body's
 * payload. There is **no** masking / 4-way clear contract on `parameters` — its
 * free-form `JsonNode` shape does not lend itself to per-field semantics
 * (Decision 14 / G14). Clients that want to preserve a value on a partial update
 * must read it via `GET` and re-send it.
 *
 * Inherits `POST /by-ids` from [EntityController]. The legacy `GET /by-parentId/{id}`
 * is **gone** (hard-break) — use `GET /api/integration-configs?namespaceId=&userId=`
 * with the `none` sentinel for `userId IS NULL` filtering.
 */
@RestController
@RequestMapping(
    "/api/integration-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class IntegrationConfigController(
    private val integrationConfigService: IntegrationConfigService,
    private val namespaceService: NamespaceService,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<IntegrationConfig, UUID, IntegrationConfigResource>(integrationConfigService, userService, permissionService) {

    override val entityType = EntityType.INTEGRATION_CONFIG

    override fun toResource(entity: IntegrationConfig): IntegrationConfigResource =
        IntegrationConfigResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            name = entity.name,
            integrationType = entity.integrationType,
            description = entity.description,
            parameters = entity.parameters,
        )

    /**
     * Not called on the unified `POST` path — [create] builds the domain entity
     * explicitly with the controller-resolved `(namespaceId, userId)`. Kept to
     * satisfy the [EntityController] contract.
     */
    override fun toDomain(resource: IntegrationConfigResource): IntegrationConfig =
        IntegrationConfig(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            userId = resource.userId,
            name = resource.name,
            integrationType = resource.integrationType,
            description = resource.description,
            parameters = resource.parameters,
        )

    private fun toDomainForUpdate(
        resource: IntegrationConfigResource,
        existing: IntegrationConfig,
    ): IntegrationConfig =
        existing.copy(
            name = resource.name,
            // integrationType is immutable post-create (Decision 18 / AC11b). The
            // merged path aligns the NS contract on the invariant the user
            // controller enforced.
            integrationType = existing.integrationType,
            description = resource.description,
            parameters = resource.parameters,
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): IntegrationConfigResource = super.getById(id)

    /**
     * POST /by-ids — overridden to honor the ownership branch (mirrors
     * [io.whozoss.agentos.aiProvider.AiProviderController.getByIds]).
     */
    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(@RequestBody ids: List<UUID>): List<IntegrationConfigResource> {
        if (ids.isEmpty()) return emptyList()

        val currentUser = userService.getCurrentUser()
        val membershipVisibleIds: Set<UUID> = if (currentUser.isAdmin) {
            ids.toSet()
        } else {
            val rawVisible = permissionService.filterVisibleIds(
                userId = currentUser.id.toString(),
                entityType = entityType,
                ids = ids.map(UUID::toString),
                action = Action.READ,
            )
            rawVisible.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
        }

        val callerId = currentUser.id
        val rows = integrationConfigService.findByIds(ids)
        val byId: Map<UUID, IntegrationConfig> = rows
            .filter { it.id in membershipVisibleIds || it.userId == callerId }
            .associateBy { it.id }
        return ids.mapNotNull { byId[it]?.let(::toResource) }
    }

    /**
     * Hard-break stub for the legacy `GET /by-parentId/{parentId}`. Hidden from
     * the OpenAPI spec so the SDK no longer surfaces `listByParentIntegrationConfig`.
     */
    @Hidden
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("isAuthenticated()")
    override fun listByParent(@PathVariable parentId: UUID): List<IntegrationConfigResource> =
        throw ResourceNotFoundException(
            "Endpoint removed; use GET /api/integration-configs?namespaceId=$parentId instead",
        )

    @Operation(
        summary = "List IntegrationConfigs by scope",
        description = "Scope is inferred from the query params :\n\n" +
            "| query                                              | mode             | required permission                            |\n" +
            "|----------------------------------------------------|------------------|------------------------------------------------|\n" +
            "| `?namespaceId=<uuid>`                              | NS-shared        | READ on the namespace (empty list if missing)  |\n" +
            "| `?namespaceId=<uuid>&userId=me`                    | user × namespace | authenticated                                  |\n" +
            "| `?namespaceId=none&userId=me`                      | user-global      | authenticated                                  |\n" +
            "| `?userId=me` (no namespace)                        | all caller's     | authenticated                                  |\n\n" +
            "`userId` accepts ONLY the literal sentinel `me` — a UUID returns 400 (cross-user " +
            "listing is not exposed). `namespaceId=none` is the sentinel for `namespaceId IS NULL`. " +
            "Pagination defaults to page=0, size=20 ; size is capped at 100.",
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        auth: Authentication,
    ): IntegrationConfigPage {
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        val resolvedNs = parseNamespaceParam(namespaceId)
        val me = userService.getCurrentUser().id
        validateUserParam(userId)

        val all = integrationConfigService.findFiltered(
            namespaceId = resolvedNs,
            namespaceIsNone = namespaceId?.equals(NONE_SENTINEL, ignoreCase = true) == true,
            callerId = me,
            userRequested = userId != null,
            canReadNamespace = { nsId -> callerCanReadNamespace(auth, nsId) },
        )

        val total = all.size
        val from = (safePage.toLong() * safeSize).coerceAtMost(total.toLong()).toInt()
        val to = min(from + safeSize, total)
        val pageItems = if (from >= to) emptyList() else all.subList(from, to)
        return IntegrationConfigPage(
            content = pageItems.map { toResource(it) },
            page = safePage,
            size = safeSize,
            totalElements = total.toLong(),
            totalPages = ((total.toLong() + safeSize - 1) / safeSize).toInt(),
        )
    }

    @Operation(
        summary = "Create an IntegrationConfig",
        description = "Scope is inferred implicitly from the body's `(namespaceId, userId)` pair :\n\n" +
            "| body.namespaceId | body.userId        | scope         | required permission                  |\n" +
            "|------------------|--------------------|---------------|--------------------------------------|\n" +
            "| null             | null               | —             | 400 Bad Request                      |\n" +
            "| present          | null               | NS-shared     | WRITE on the namespace               |\n" +
            "| null             | <currentUser.id>   | user-global   | authenticated only                   |\n" +
            "| present          | <currentUser.id>   | user×namespace| READ on the namespace                |\n\n" +
            "`body.userId` (when supplied) MUST equal the authenticated user's id — sending a different " +
            "user-id is rejected with 400 (mass-assignment guard, Decision 15). A `namespaceId` that does " +
            "not exist returns 404.",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(@Valid @RequestBody resource: IntegrationConfigResource): IntegrationConfigResource {
        val me = userService.getCurrentUser().id

        // Phase 1 — mass-assignment guard
        if (resource.userId != null && resource.userId != me) {
            throw BadRequestException("userId in body must match authenticated user or be omitted")
        }

        // Phase 2 — scope determination
        val resolvedNs: UUID? = resource.namespaceId
        val resolvedUser: UUID? = if (resource.userId != null) me else null
        if (resolvedNs == null && resolvedUser == null) {
            throw BadRequestException("must provide namespaceId, userId, or both")
        }

        // Phase 3 — per-scope authorization, run BEFORE the existence check so
        // unauthorised callers always get 403 regardless of namespace existence
        // (closes the 404-vs-403 existence oracle on POST).
        val authzAction: Action? = when {
            resolvedNs != null && resolvedUser != null -> Action.READ
            resolvedNs != null && resolvedUser == null -> Action.WRITE
            else -> null
        }
        if (authzAction != null && resolvedNs != null) {
            val granted = permissionService.hasPermission(
                userId = me.toString(),
                entityType = EntityType.NAMESPACE,
                entityId = resolvedNs.toString(),
                action = authzAction,
            )
            if (!granted) {
                throw AccessDeniedException(
                    "Cannot create IntegrationConfig in namespace $resolvedNs (${authzAction.name} required)",
                )
            }
        }

        // Phase 3.5 — namespace existence check (deferred after Phase 3 to avoid
        // leaking namespace existence to non-members ; still required because the
        // super-admin bypass in PermissionService.hasPermission returns true for
        // dangling namespaceIds).
        if (resolvedNs != null && namespaceService.findById(resolvedNs) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Namespace not found: $resolvedNs")
        }

        // Phase 4 — explicit domain build
        val target = IntegrationConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = resolvedNs,
            userId = resolvedUser,
            name = resource.name,
            integrationType = resource.integrationType,
            description = resource.description,
            parameters = resource.parameters,
        )
        return toResource(integrationConfigService.create(target))
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: IntegrationConfigResource,
    ): IntegrationConfigResource {
        val existing = integrationConfigService.findById(id)
            ?: throw ResourceNotFoundException("IntegrationConfig not found: $id")
        return toResource(integrationConfigService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'IntegrationConfig', 'DELETE')")
    @HideOnAccessDenied
    override fun delete(@PathVariable id: UUID) = super.delete(id)

    private fun callerCanReadNamespace(auth: Authentication, namespaceId: UUID): Boolean =
        permissionService.hasPermission(
            userId = userService.getCurrentUser().id.toString(),
            entityType = EntityType.NAMESPACE,
            entityId = namespaceId.toString(),
            action = Action.READ,
        )

    /**
     * Parse the `namespaceId` query parameter. Returns `null` for absent or `none` sentinel,
     * a valid UUID otherwise.
     */
    private fun parseNamespaceParam(raw: String?): UUID? = when {
        raw == null -> null
        raw.equals(NONE_SENTINEL, ignoreCase = true) -> null
        else -> runCatching { UUID.fromString(raw) }
            .getOrElse { throw BadRequestException("Invalid namespaceId: '$raw'") }
    }

    /**
     * Validate the `userId` query parameter. Only `me` and absent are valid;
     * any other value is rejected with 400.
     */
    private fun validateUserParam(raw: String?) {
        if (raw != null && !raw.equals(ME_SENTINEL, ignoreCase = true)) {
            throw BadRequestException(
                "Invalid userId filter: '$raw' — only 'me' is supported (cross-user listing is not exposed)",
            )
        }
    }

    companion object : KLogging() {
        const val NONE_SENTINEL = "none"
        const val ME_SENTINEL = "me"
        const val MAX_PAGE_SIZE = 100
    }
}

/**
 * Pagination envelope for [IntegrationConfigController.list]. Kept narrow on purpose —
 * Spring Data's `Page<T>` would couple the API to a JPA-flavoured shape we do not need here.
 */
data class IntegrationConfigPage(
    val content: List<IntegrationConfigResource>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
