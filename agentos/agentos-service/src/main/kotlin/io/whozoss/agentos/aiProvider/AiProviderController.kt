package io.whozoss.agentos.aiProvider

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserService
import jakarta.validation.Valid
import mu.KLogging
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
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
 * Unified REST API for [AiProvider] entities — covers the **three scopes** that the
 * 3-tier reconciliation distinguishes (NS-shared, user × namespace, user-global).
 *
 * **Implicit scope dispatch on `POST` (Decision 15)** — the controller infers the
 * scope from the `(body.namespaceId, body.userId)` pair rather than from a discrete
 * `scope` field. Phases :
 *  1. Mass-assignment guard : `body.userId`, when non-null, must equal
 *     `auth.principal.userId`. Otherwise → 400.
 *  2. Scope determination :
 *     - both null → 400 ("must provide namespaceId, userId, or both") ;
 *     - `(ns, null)` → NS-shared ;
 *     - `(null, user)` → user-global ;
 *     - `(ns, user)` → user × namespace.
 *  3. Namespace existence check (when `ns != null`) — `namespaceService.findById`
 *     returns null → 404. Required because `permissionService.hasPermission` short-
 *     circuits to `true` for super-admins even on dangling namespaceIds.
 *  4. Per-scope authorization :
 *     - NS-shared → `hasPermission(ns, NAMESPACE, WRITE)` ;
 *     - user × ns → `hasPermission(ns, NAMESPACE, READ)` ;
 *     - user-global → `isAuthenticated()` (covered by class-level @PreAuthorize).
 *  5. Domain build is **explicit** : the persisted entity uses the controller-resolved
 *     `(namespaceId, userId)` — never the raw body — so a future evolution of
 *     [toDomain] cannot reintroduce the silent stripping pattern from PR #837.
 *
 * **Authorization on read / update / delete** uses `@PreAuthorize("hasPermission(#id, 'AiProvider', ACTION)")` —
 * [io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator] tries the
 * membership / super-admin path first, then falls through to ownership
 * (`provider.userId == auth.userId`) for [EntityType.AI_PROVIDER]. No per-controller
 * Guard component is needed.
 *
 * **Existence-hiding** : every authz-protected endpoint (incl. `PUT` / `DELETE`) is
 * annotated [HideOnAccessDenied] so cross-user probes return 404 instead of 403 —
 * indistinguishable from a missing row.
 *
 * **Mass-assignment guards** :
 *  - On `POST`, the persisted `userId` is `currentUserId(auth)` (Phase 1 has already
 *    asserted that the body, if it carries a userId, agrees with the principal).
 *  - On `PUT`, `id`, `namespaceId`, `userId` and `apiType` are preserved from the
 *    persisted row via `existing.copy(...)`. `apiType` is immutable post-create
 *    (Decision 10 / AC11) — aligns NS path on the invariant the user controller
 *    already enforced.
 *
 * **`apiKey` 4-way semantics on `PUT`** — `null` / masked sentinel preserve, `""`
 * clears, non-blank replaces. See [resolveApiKey].
 *
 * Inherits `POST /by-ids` from [EntityController]. The legacy `GET /by-parentId/{id}`
 * is **gone** (hard-break) — use `GET /api/ai-providers?namespaceId=&userId=` with
 * the `none` sentinel for `userId IS NULL` filtering.
 */
@RestController
@RequestMapping(
    "/api/ai-providers",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AiProviderController(
    private val aiProviderService: AiProviderService,
    private val namespaceService: NamespaceService,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<AiProvider, UUID, AiProviderResource>(aiProviderService, userService, permissionService) {

    override val entityType = EntityType.AI_PROVIDER

    override fun toResource(entity: AiProvider): AiProviderResource =
        AiProviderResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            name = entity.name,
            description = entity.description,
            apiType = entity.apiType,
            baseUrl = entity.baseUrl,
            apiKey = maskApiKey(entity.apiKey),
        )

    /**
     * Not called on the unified `POST` path — [create] builds the domain entity
     * explicitly with the controller-resolved `(namespaceId, userId)` to keep the
     * scope-decision visible at the call site (Decision 15, Phase 4). Kept to
     * satisfy the [EntityController] contract for any future callers.
     */
    override fun toDomain(resource: AiProviderResource): AiProvider =
        AiProvider(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            namespaceId = resource.namespaceId,
            userId = resource.userId,
            name = resource.name,
            description = resource.description,
            apiType = resource.apiType!!,
            baseUrl = resource.baseUrl,
            apiKey = resource.apiKey,
        )

    private fun toDomainForUpdate(
        resource: AiProviderResource,
        existing: AiProvider,
    ): AiProvider =
        existing.copy(
            name = resource.name,
            description = resource.description,
            // apiType is immutable post-create (Decision 10 / AC11). The merged path
            // aligns the NS contract on the invariant the user controller enforced.
            apiType = existing.apiType,
            baseUrl = resource.baseUrl,
            apiKey = resolveApiKey(resource.apiKey, existing.apiKey),
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiProvider', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): AiProviderResource = super.getById(id)

    // POST /by-ids — inherited from EntityController.getByIds (story 5-4 factorisation).

    /**
     * Hard-break stub for the legacy `GET /by-parentId/{parentId}` inherited from
     * [EntityController.listByParent]. Hidden from the OpenAPI spec so the SDK no longer
     * surfaces `listByParentAiProvider`, and any direct caller gets a 404 with a
     * pointer to the unified `?namespaceId=` route. Kept guarded by `isAuthenticated()`
     * so the URL never bypasses the security filter chain.
     */
    @Hidden
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("isAuthenticated()")
    override fun listByParent(@PathVariable parentId: UUID): List<AiProviderResource> =
        throw ResourceNotFoundException(
            "Endpoint removed; use GET /api/ai-providers?namespaceId=$parentId instead",
        )

    /**
     * GET — list providers in one of three modes :
     *  - `?namespaceId=<uuid>` (no `userId`) → NS-shared layer of that namespace.
     *  - `?namespaceId=<uuid>&userId=me` → caller's user × namespace overlays for that namespace.
     *  - `?namespaceId=none&userId=me` → caller's user-global overlays.
     *
     * `userId` accepts only the literal `me` sentinel (no UUID) — listing **another**
     * user's overlays is intentionally not supported via this route (mass-assignment
     * guard, AC4). `namespaceId=none` matches `namespaceId IS NULL`.
     *
     * Returns a paginated envelope with default page = 0 / size = 20, capped at
     * [MAX_PAGE_SIZE].
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        auth: Authentication,
    ): AiProviderPage {
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        val nsFilter = parseNamespaceFilter(namespaceId)
        val userFilter = parseUserFilter(userId, auth)

        val all: List<AiProvider> = when {
            // NS-shared layer of a specific namespace : reuse the existing scope-aware service
            // call so namespace-membership SpEL still gates this view (callers must have READ
            // on the namespace ; an unauthorised caller gets an empty list rather than 403,
            // because we want the surface to feel consistent with the user-overlay views).
            nsFilter is NamespaceFilter.Specific && userFilter is UserFilter.Any ->
                aiProviderService.findByNamespaceId(nsFilter.target)
                    .filter { it.userId == null }

            // User-overlays (any combination of NS-specific / user-global / both) : start
            // from the per-user listing and let the namespace filter narrow down.
            userFilter is UserFilter.CurrentUser ->
                aiProviderService.findByUserId(userFilter.id)
                    .filter { nsFilter.accepts(it.namespaceId) }

            // No filter at all : surface only the caller's overlays. Listing every NS-shared
            // row across the platform is reserved to dedicated admin endpoints we don't expose
            // here (would require the super-admin bypass at the call site).
            else -> {
                val me = currentUserId(auth)
                aiProviderService.findByUserId(me).filter { nsFilter.accepts(it.namespaceId) }
            }
        }

        val total = all.size
        val from = (safePage.toLong() * safeSize).coerceAtMost(total.toLong()).toInt()
        val to = min(from + safeSize, total)
        val pageItems = if (from >= to) emptyList() else all.subList(from, to)
        return AiProviderPage(
            content = pageItems.map { toResource(it) },
            page = safePage,
            size = safeSize,
            totalElements = total.toLong(),
            totalPages = ((total.toLong() + safeSize - 1) / safeSize).toInt(),
        )
    }

    @Operation(
        summary = "Create an AiProvider",
        description = "Scope is inferred implicitly from the body's `(namespaceId, userId)` pair :\n\n" +
            "| body.namespaceId | body.userId        | scope         | required permission                  |\n" +
            "|------------------|--------------------|---------------|--------------------------------------|\n" +
            "| null             | null               | —             | 400 Bad Request                      |\n" +
            "| present          | null               | NS-shared     | WRITE on the namespace               |\n" +
            "| null             | <currentUser.id>   | user-global   | authenticated only                   |\n" +
            "| present          | <currentUser.id>   | user×namespace| READ on the namespace                |\n\n" +
            "`body.userId` (when supplied) MUST equal the authenticated user's id — sending a different " +
            "user-id is rejected with 400 (mass-assignment guard, Decision 15 / AC2-AC3). A `namespaceId` " +
            "that does not exist returns 404 (Decision 15 / AC7).",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    override fun create(@Valid @RequestBody resource: AiProviderResource): AiProviderResource {
        val me = currentUserId(currentAuth())

        // Phase 1 — mass-assignment guard
        if (resource.userId != null && resource.userId != me) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "userId in body must match authenticated user or be omitted",
            )
        }

        // Phase 2 — scope determination
        val resolvedNs: UUID? = resource.namespaceId
        val resolvedUser: UUID? = if (resource.userId != null) me else null
        if (resolvedNs == null && resolvedUser == null) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "must provide namespaceId, userId, or both",
            )
        }

        // Phase 2.5 — namespace existence check. Required because the super-admin
        // bypass in PermissionService.hasPermission returns true even for dangling
        // namespaceIds, which would otherwise let an admin create dangling FK rows.
        if (resolvedNs != null && namespaceService.findById(resolvedNs) == null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Namespace not found: $resolvedNs")
        }

        // Phase 3 — per-scope authorization. user-global needs nothing beyond the
        // class-level isAuthenticated() ; NS-touching scopes need a permission check
        // on the namespace.
        val authzAction: Action? = when {
            resolvedNs != null && resolvedUser != null -> Action.READ      // user × ns : READ on the NS suffices
            resolvedNs != null && resolvedUser == null -> Action.WRITE     // NS-shared : ADMIN required
            else -> null                                                   // user-global : isAuthenticated() suffices
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
                    "Cannot create AiProvider in namespace $resolvedNs (${authzAction.name} required)",
                )
            }
        }

        // Phase 4 — explicit domain build (never re-read the body for scope fields)
        val target = AiProvider(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = resolvedNs,
            userId = resolvedUser,
            name = resource.name,
            description = resource.description,
            apiType = resource.apiType!!,
            baseUrl = resource.baseUrl,
            apiKey = resource.apiKey,
        )
        return toResource(aiProviderService.create(target))
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AiProvider', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AiProviderResource,
    ): AiProviderResource {
        val existing = aiProviderService.findById(id)
            ?: throw ResourceNotFoundException("AiProvider not found: $id")
        return toResource(aiProviderService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiProvider', 'DELETE')")
    @HideOnAccessDenied
    override fun delete(@PathVariable id: UUID) = super.delete(id)

    /**
     * Three-way semantics for the `apiKey` field on update (FR25, NFR-SEC-1):
     *
     * - The masked sentinel ("****" pattern returned by [maskApiKey]) → preserve the persisted
     *   credential. This guards round-trips where the FE re-sends a value it loaded from a GET.
     * - `null` → preserve. Wire contract: the FE omits the field entirely when the user did not
     *   touch the input. Jackson collapses JSON-null and field-absent into a Kotlin `null`, so
     *   this branch handles both transparently.
     * - Blank string ("") → clear the persisted credential. Wire contract: an explicit empty
     *   string in the body means the user deliberately wiped the field.
     * - Non-blank string → replace.
     */
    private fun resolveApiKey(incoming: String?, current: String?): String? = when {
        isMasked(incoming) -> current
        incoming == null -> current
        incoming.isBlank() -> null
        else -> incoming
    }

    private fun currentUserId(auth: Authentication): UUID {
        val raw = auth.name ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication")
        return runCatching { UUID.fromString(raw) }
            .getOrElse {
                logger.warn { "[AiProviderController] auth.name is not a UUID: '$raw'" }
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication identifier")
            }
    }

    private fun currentAuth(): Authentication =
        SecurityContextHolder.getContext().authentication
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication")

    private fun parseNamespaceFilter(raw: String?): NamespaceFilter = when {
        raw == null -> NamespaceFilter.Any
        raw.equals(NONE_SENTINEL, ignoreCase = true) -> NamespaceFilter.UserGlobalOnly
        else -> {
            val parsed = runCatching { UUID.fromString(raw) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid namespaceId: '$raw'") }
            NamespaceFilter.Specific(parsed)
        }
    }

    private fun parseUserFilter(raw: String?, auth: Authentication): UserFilter = when {
        raw == null -> UserFilter.Any
        raw.equals(ME_SENTINEL, ignoreCase = true) -> UserFilter.CurrentUser(currentUserId(auth))
        else -> throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid userId filter: '$raw' — only 'me' is supported (cross-user listing is not exposed)",
        )
    }

    private sealed class NamespaceFilter {
        abstract fun accepts(namespaceId: UUID?): Boolean

        data object Any : NamespaceFilter() {
            override fun accepts(namespaceId: UUID?): Boolean = true
        }

        data object UserGlobalOnly : NamespaceFilter() {
            override fun accepts(namespaceId: UUID?): Boolean = namespaceId == null
        }

        data class Specific(val target: UUID) : NamespaceFilter() {
            override fun accepts(namespaceId: UUID?): Boolean = namespaceId == target
        }
    }

    private sealed class UserFilter {
        data object Any : UserFilter()

        data class CurrentUser(val id: UUID) : UserFilter()
    }

    companion object : KLogging() {
        const val NONE_SENTINEL = "none"
        const val ME_SENTINEL = "me"
        const val MAX_PAGE_SIZE = 100
    }
}

/**
 * Pagination envelope for [AiProviderController.list]. Kept narrow on purpose —
 * Spring Data's `Page<T>` would couple the API to a JPA-flavoured shape we do not need here.
 */
data class AiProviderPage(
    val content: List<AiProviderResource>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
