package io.whozoss.agentos.aiModel

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.entity.EntityController
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.aiProvider.AiModel
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
import io.whozoss.agentos.exception.BadRequestException
import java.util.UUID
import kotlin.math.min

/**
 * Unified REST API for [AiModel] entities — covers the **three scopes** (NS-shared,
 * user × namespace, user-global).
 *
 * **Scope is server-resolved** : the client sends only `aiProviderId` in the body ; the
 * service denormalises `namespaceId` + `userId` from the parent provider at create time.
 * Unlike [io.whozoss.agentos.aiProvider.AiProviderController] (Decision 15 implicit-scope
 * dispatch), there is no scope determination phase here — the scope is fully determined by
 * the parent AiProvider already persisted.
 *
 * **Authorization on create** : `@PreAuthorize("isAuthenticated()")` + programmatic verdict
 * via [AiModelGuard.canCreateVerdict]. Three outcomes : Ok (proceed), ParentInvisible (404
 * via `@HideOnAccessDenied`), ParentNotWritable (403 explicit).
 *
 * **Authorization on read/update/delete** : `@PreAuthorize("hasPermission(#id, 'AiModel', ACTION)")`
 * resolved by [io.whozoss.agentos.security.declarative.AgentOsPermissionEvaluator] via the
 * membership path first, then the ownership branch (AI_MODEL now in OWNERSHIP_AWARE_TYPES).
 *
 * **Existence-hiding** : `@HideOnAccessDenied` on GET / PUT / DELETE so cross-user probes
 * return 404 instead of 403 (Decision 20 cohérence with AiProvider/IntegrationConfig).
 *
 * **`body.namespaceId`/`body.userId` silently ignored on POST** (Decision 14 — SF4 fix) :
 * these fields are server-side denormalized ; the controller strips them to null before
 * delegating to the service. The service will denormalize from the parent provider.
 *
 * Inherits `POST /by-ids` from [EntityController], overridden here to include the ownership
 * branch (F2 fix). The legacy `GET /by-parentId/{parentId}` is hidden and returns 404.
 * The legacy `GET /by-namespaceId/{namespaceId}` is removed (use `?namespaceId=` instead).
 */
@RestController
@RequestMapping(
    "/api/ai-models",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class AiModelController(
    private val aiModelService: AiModelService,
    private val aiModelGuard: AiModelGuard,
    userService: UserService,
    permissionService: PermissionService,
) : EntityController<AiModel, UUID, AiModelResource>(aiModelService, userService, permissionService) {

    override val entityType = EntityType.AI_MODEL

    override fun toResource(entity: AiModel): AiModelResource =
        AiModelResource(
            id = entity.metadata.id,
            aiProviderId = entity.aiProviderId,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            apiModelName = entity.apiModelName,
            description = entity.description,
            alias = entity.alias,
            priority = entity.priority,
            temperature = entity.temperature,
            maxTokens = entity.maxTokens,
        )

    /**
     * Convert a resource to a domain entity for **create** only. [namespaceId] and
     * [userId] are forced to null here — the service denormalises them from the parent
     * AiProvider (SF4 silent-strip : any body values for these fields are ignored).
     */
    override fun toDomain(resource: AiModelResource): AiModel =
        AiModel(
            metadata = EntityMetadata(id = resource.id ?: UUID.randomUUID()),
            aiProviderId = resource.aiProviderId!!,
            namespaceId = null,
            userId = null,
            apiModelName = resource.apiModelName,
            description = resource.description,
            alias = resource.alias,
            priority = resource.priority,
            temperature = resource.temperature,
            maxTokens = resource.maxTokens,
        )

    private fun toDomainForUpdate(
        resource: AiModelResource,
        existing: AiModel,
    ): AiModel =
        existing.copy(
            apiModelName = resource.apiModelName,
            description = resource.description,
            alias = resource.alias,
            priority = resource.priority,
            temperature = resource.temperature,
            maxTokens = resource.maxTokens,
        )

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiModel', 'READ')")
    @HideOnAccessDenied
    override fun getById(@PathVariable id: UUID): AiModelResource = super.getById(id)

    /**
     * POST /by-ids — overridden to honour the ownership branch (F2 fix).
     *
     * The base implementation only consults membership edges. Owner-only rows (user overlays)
     * would be invisible through this batch endpoint even though `GET /{id}` returns them via
     * the evaluator's ownership branch. The override fetches all rows once and unions
     * membership-visible ids with rows the caller owns — bounded by input size.
     */
    @PostMapping(
        "/by-ids",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @PreAuthorize("isAuthenticated()")
    override fun getByIds(@RequestBody ids: List<UUID>): List<AiModelResource> {

        if (ids.isEmpty()) return emptyList()

        val currentUser = userService.getCurrentUser()
        val membershipVisibleIds: Set<UUID> = when {
            currentUser.isAdmin -> ids.toSet()
            else -> {
                val rawVisible = permissionService.filterVisibleIds(
                    userId = currentUser.id.toString(),
                    entityType = entityType,
                    ids = ids.map(UUID::toString),
                    action = Action.READ,
                )
                rawVisible.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }.toSet()
            }
        }

        val callerId = currentUser.id
        val rows = aiModelService.findByIds(ids)
        val byId = rows
            .filter { it.metadata.id in membershipVisibleIds || it.userId == callerId }
            .associateBy { it.metadata.id }
        return ids.mapNotNull { byId[it]?.let(::toResource) }
    }

    /**
     * Hard-break stub for the legacy `GET /by-parentId/{parentId}`.
     * Hidden from OpenAPI ; any direct caller receives 404 with a migration pointer.
     */
    @Hidden
    @GetMapping("/by-parentId/{parentId}")
    @PreAuthorize("isAuthenticated()")
    override fun listByParent(@PathVariable parentId: UUID): List<AiModelResource> =
        throw ResourceNotFoundException(
            "Endpoint removed; use GET /api/ai-models?aiProviderId=$parentId instead",
        )

    @Operation(
        summary = "Create an AiModel",
        description = "Scope is inferred server-side from the parent `aiProviderId` — NOT from body fields :\n\n" +
            "| body.aiProviderId's parent scope | required permission               |\n" +
            "|----------------------------------|-----------------------------------|\n" +
            "| NS-shared                        | WRITE on parent's namespace       |\n" +
            "| user-global (userId IS NOT NULL) | caller must own the parent (same userId) |\n" +
            "| user × namespace                 | caller must own the parent (same userId) |\n\n" +
            "`namespaceId` and `userId` in body are **silently ignored** — scope is denormalized " +
            "server-side from the parent AiProvider via `AiModelServiceImpl.create()`. " +
            "Missing or cross-user parent returns 404 (existence-hiding). " +
            "NS-shared parent without WRITE returns 403.",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @HideOnAccessDenied
    override fun create(@Valid @RequestBody resource: AiModelResource): AiModelResource {
        val auth = SecurityContextHolder.getContext().authentication ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication")
        ensureCallerAuth(auth)

        when (aiModelGuard.canCreateVerdict(resource, auth)) {
            is AiModelGuard.CreateVerdict.Ok -> Unit
            is AiModelGuard.CreateVerdict.ParentInvisible ->
                throw AccessDeniedException("Parent AiProvider not visible")
            is AiModelGuard.CreateVerdict.ParentNotWritable ->
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Cannot create AiModel under namespace-shared parent without WRITE on its namespace",
                )
        }
        return toResource(
            try {
                aiModelService.create(toDomain(resource))
            } catch (e: ResourceNotFoundException) {
                throw AccessDeniedException("Parent AiProvider not visible", e)
            }
        )
    }

    @Operation(
        summary = "List AiModels by scope",
        description = "Scope is inferred from the query params. All combinations that touch `?aiProviderId=` " +
            "first check provider visibility via `canSeeProvider` (empty list if false).\n\n" +
            "| namespaceId | userId | aiProviderId | mode | permission |\n" +
            "|---|---|---|---|---|\n" +
            "| UUID | null | null | NS-shared of `nsX` | READ on namespace (empty if missing) |\n" +
            "| UUID | `me` | null | user × namespace | authenticated |\n" +
            "| `none` | `me` | null | user-global | authenticated |\n" +
            "| null | `me` | null | all caller's overlays | authenticated |\n" +
            "| null | null | UUID | all visible under parent | canSeeProvider |\n" +
            "| UUID | null | UUID | NS-shared of `nsX` filtered by parent | READ on namespace + canSeeProvider |\n" +
            "| UUID | `me` | UUID | user × namespace filtered by parent | authenticated + canSeeProvider |\n" +
            "| `none` | `me` | UUID | user-global filtered by parent | authenticated + canSeeProvider |\n" +
            "| null | null | null | default: caller's overlays | authenticated |\n\n" +
            "`userId` accepts ONLY the literal sentinel `me` — a UUID returns 400. " +
            "`namespaceId=none` is the sentinel for `namespaceId IS NULL`. " +
            "Pagination defaults to page=0, size=20 ; size is capped at 100.",
    )
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) aiProviderId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        auth: Authentication,
    ): AiModelPage {
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        val resolvedNs = parseNamespaceParam(namespaceId)
        val me = userService.getCurrentUser().id
        validateUserParam(userId)

        val rows = aiModelService.findFiltered(
            namespaceId = resolvedNs,
            namespaceIsNone = namespaceId?.equals(NONE_SENTINEL, ignoreCase = true) == true,
            callerId = me,
            userRequested = userId != null,
            aiProviderId = aiProviderId,
            canReadNamespace = { nsId -> callerCanReadNamespace(auth, nsId) },
            canSeeProvider = { providerId -> aiModelGuard.canSeeProvider(providerId, auth) },
        )

        val total = rows.size
        val from = (safePage.toLong() * safeSize).coerceAtMost(total.toLong()).toInt()
        val to = min(from + safeSize, total)
        val pageItems = if (from >= to) emptyList() else rows.subList(from, to)
        return AiModelPage(
            content = pageItems.map { toResource(it) },
            page = safePage,
            size = safeSize,
            totalElements = total.toLong(),
            totalPages = ((total.toLong() + safeSize - 1) / safeSize).toInt(),
        )
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#id, 'AiModel', 'WRITE')")
    @HideOnAccessDenied
    override fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody resource: AiModelResource,
    ): AiModelResource {
        val existing = aiModelService.findById(id)
            ?: throw ResourceNotFoundException("AiModel not found: $id")
        return toResource(aiModelService.update(toDomainForUpdate(resource, existing)))
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(#id, 'AiModel', 'DELETE')")
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
     * Validate caller authentication without consuming the resulting UUID.
     *
     * Used by [create] to enforce the SF3 invariant: a malformed `auth.name` (e.g. an
     * email leaked through Cloudflare's auth-mode JWT) must throw 401 BEFORE the
     * `canCreateVerdict` eval, so the verdict layer never sees a bogus caller id and
     * the caller receives a 401 rather than a misleading 404 from `ParentInvisible`.
     */
    private fun ensureCallerAuth(auth: Authentication) {
        userService.getCurrentUser().id
    }


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
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Invalid userId filter: only 'me' is supported",
            )
        }
    }

    companion object : KLogging() {
        const val NONE_SENTINEL = "none"
        const val ME_SENTINEL = "me"
        const val MAX_PAGE_SIZE = 100
    }
}

data class AiModelPage(
    val content: List<AiModelResource>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
