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
import io.whozoss.agentos.exception.BadRequestException
import java.util.UUID

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
 *  3. Per-scope authorization (run BEFORE existence so unauthorised callers always
 *     get 403, never an existence-leak 404) :
 *     - NS-shared → `hasPermission(ns, NAMESPACE, WRITE)` ;
 *     - user × ns → `hasPermission(ns, NAMESPACE, READ)` ;
 *     - user-global → `isAuthenticated()` (covered by class-level @PreAuthorize).
 *  4. Namespace existence check (when `ns != null`) — `namespaceService.findById`
 *     returns null → 404. Still required (after authz) because the super-admin
 *     bypass in `permissionService.hasPermission` returns `true` for dangling ids,
 *     which would otherwise let an admin create dangling FK rows.
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
 *  - On `POST`, the persisted `userId` is `userService.getCurrentUser().id` (Phase 1 has already
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

    /**
     * POST /by-ids — overridden to honor the ownership branch.
     *
     * The base implementation calls [io.whozoss.agentos.permissions.PermissionService.filterVisibleIds],
     * which only consults `PermissionRelation` edges (MEMBER / ADMIN / super-admin). Owner-only
     * rows (a user's overlays) would therefore be invisible through this batch endpoint, even
     * though `GET /{id}` returns them via the evaluator's ownership branch
     * ([io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator]). The override fetches
     * the rows once and unions the membership-visible set with rows the caller owns — bounded by
     * the input size (no full scan of the user's overlays).
     */
    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(@RequestBody ids: List<UUID>): List<AiProviderResource> {

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

        // Single DB fetch for all candidate rows ; filter for visibility via membership OR ownership.
        // Bounded by input size so the cost stays O(ids.size), not O(user's overlays).
        val callerId = currentUser.id
        val rows = aiProviderService.findByIds(ids)
        val byId: Map<UUID, AiProvider> = rows
            .filter { it.metadata.id in membershipVisibleIds || it.userId == callerId }
            .associateBy { it.metadata.id }
        return ids.mapNotNull { byId[it]?.let(::toResource) }
    }

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

    @Operation(
        summary = "List AiProviders by scope",
        description = "Scope is inferred from the query params :\n\n" +
            "| query                                              | mode             | required permission                            |\n" +
            "|----------------------------------------------------|------------------|------------------------------------------------|\n" +
            "| `?namespaceId=<uuid>`                              | NS-shared        | READ on the namespace (empty list if missing)  |\n" +
            "| `?namespaceId=<uuid>&userId=me`                    | user × namespace | authenticated                                  |\n" +
            "| `?namespaceId=none&userId=me`                      | user-global      | authenticated                                  |\n" +
            "| `?userId=me` (no namespace)                        | all caller's     | authenticated                                  |\n\n" +
            "`userId` accepts ONLY the literal sentinel `me` — a UUID returns 400 (cross-user " +
            "listing is not exposed, mass-assignment guard, AC4). `namespaceId=none` is the " +
            "sentinel for `namespaceId IS NULL`.",
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
        auth: Authentication,
    ): List<AiProviderResource> {
        val resolvedNs = parseNamespaceParam(namespaceId)
        val me = userService.getCurrentUser().id
        validateUserParam(userId)

        val all = aiProviderService.findFiltered(
            namespaceId = resolvedNs,
            namespaceIsNone = namespaceId?.equals(NONE_SENTINEL, ignoreCase = true) == true,
            callerId = me,
            userRequested = userId != null,
            canReadNamespace = { nsId -> callerCanReadNamespace(nsId) },
        )

        return all.map { toResource(it) }
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
        // (closes the 404-vs-403 existence-oracle on POST). user-global needs
        // nothing beyond the class-level isAuthenticated() ; NS-touching scopes
        // need a permission check on the namespace.
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

        // Phase 3.5 — namespace existence check (was Phase 2.5 ; deferred so non-
        // members hit 403 in Phase 3 before the existence oracle fires). Still
        // required because the super-admin bypass in PermissionService.hasPermission
        // returns true even for dangling namespaceIds — without this, a super-admin
        // POST'ing with an unknown namespaceId would create a dangling FK row.
        if (resolvedNs != null && namespaceService.findById(resolvedNs) == null) {
            throw ResourceNotFoundException("Namespace not found: $resolvedNs")
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

    private fun callerCanReadNamespace(namespaceId: UUID): Boolean =
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
    }
}
