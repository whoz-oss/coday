package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.sdk.entity.EntityMetadata
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
 * REST API for user-scoped [IntegrationConfig] entities — the personal Tool overrides
 * that allow a MEMBER to override or supplement a namespace-shared config without
 * admin intervention (Epic 6).
 *
 * **Why not extend `EntityController`** — `EntityController` presupposes namespace-
 * membership-based authorization (`hasPermission(parentId, 'Namespace', 'READ')`).
 * User-scoped overrides are ownership-based (`cfg.userId == auth.name`), so this
 * controller is a flat `@RestController` that delegates authz to [UserIntegrationConfigGuard].
 *
 * **Mass-assignment guard** (FR19, FR24, AR6, AR14) — `userId` from the request body is
 * always ignored. On create it is forced to `auth.name`; on update it is preserved from
 * the persisted row via `existing.copy(...)`. Same for `namespaceId`, `id`, `integrationType`
 * on update.
 *
 * **404 vs 403** (FR21, NFR-SEC-2, AR7) — read/update/delete endpoints are annotated
 * `@HideOnAccessDenied`: when the Guard refuses (cross-user access, or the row does not
 * exist), the resulting [AccessDeniedException] is translated to **404 Not Found** by
 * [io.whozoss.agentos.security.declarative.AccessDeniedExceptionHandler] — indistinguishable
 * from a missing row, preventing existence enumeration.
 */
@RestController
@RequestMapping(
    "/api/user-integration-configs",
    produces = [MediaType.APPLICATION_JSON_VALUE],
)
class UserIntegrationConfigController(
    private val service: IntegrationConfigService,
    private val guard: UserIntegrationConfigGuard,
) {

    /**
     * POST — create a user-scoped override.
     *
     * @PreAuthorize("isAuthenticated()") rejects anonymous callers up-front. Per-namespace
     * authorization (when `body.namespaceId != null`) lives in [UserIntegrationConfigGuard.canCreate]
     * and surfaces as **403 Forbidden** because the caller submitted the namespaceId knowingly —
     * 404 would be misleading.
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody body: UserIntegrationConfigResource,
        auth: Authentication,
    ): UserIntegrationConfigResource {
        val me = currentUserId(auth)
        val target = IntegrationConfig(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = body.namespaceId,
            // Mass-assignment guard: the body's userId is discarded — only auth.name decides ownership.
            userId = me,
            name = body.name,
            integrationType = body.integrationType,
            description = body.description,
            parameters = body.parameters,
        )
        if (!guard.canCreate(target, auth)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Cannot create user-integration-config in namespace ${body.namespaceId} (READ permission required)",
            )
        }
        return toResource(service.create(target))
    }

    /**
     * GET /{id} — fetch one of the caller's own configs.
     * Cross-user access surfaces as 404 via @HideOnAccessDenied (existence-hiding).
     */
    @GetMapping("/{id}")
    @HideOnAccessDenied
    fun getById(
        @PathVariable id: UUID,
        auth: Authentication,
    ): UserIntegrationConfigResource {
        val cfg = service.findById(id) ?: throw AccessDeniedException("UserIntegrationConfig $id not visible")
        if (!guard.canRead(cfg, auth)) {
            throw AccessDeniedException("UserIntegrationConfig $id not visible")
        }
        return toResource(cfg)
    }

    /**
     * GET — list the caller's own configs, optionally filtered by namespace mode.
     *
     * The `namespaceId` query parameter is typed as `String` because it accepts the
     * literal sentinel `"none"` to filter on `namespaceId IS NULL` (user-global only).
     * `UUID.fromString("none")` would throw, hence the manual parsing.
     *
     * Pagination is in-memory: a user typically holds < 100 overrides, and the underlying
     * `findByUserId` already filters by index `(userId)` + soft-delete. DB-side pagination
     * is tracked as TODO for story 6.4.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        auth: Authentication,
    ): UserIntegrationConfigPage {
        val me = currentUserId(auth)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        val filter = parseNamespaceFilter(namespaceId)
        // Force userId=me — any client-supplied userId param is ignored (mass-assignment guard, FR20).
        val all = service.findByUserId(me).filter { filter.accepts(it.namespaceId) }
        val total = all.size
        // Long arithmetic: safePage can be Int.MAX_VALUE → safePage*safeSize would overflow Int.
        val from = (safePage.toLong() * safeSize).coerceAtMost(total.toLong()).toInt()
        val to = min(from + safeSize, total)
        val pageItems = if (from >= to) emptyList() else all.subList(from, to)
        return UserIntegrationConfigPage(
            content = pageItems.map { toResource(it) },
            page = safePage,
            size = safeSize,
            totalElements = total.toLong(),
            totalPages = ((total.toLong() + safeSize - 1) / safeSize).toInt(),
        )
    }

    /**
     * PUT /{id} — update a user-owned config.
     *
     * Mass-assignment guard: `id`, `namespaceId`, `userId`, `integrationType` are preserved
     * from the persisted row; only `name`, `description`, `parameters` are taken from the body.
     */
    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @HideOnAccessDenied
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UserIntegrationConfigResource,
        auth: Authentication,
    ): UserIntegrationConfigResource {
        val existing = service.findById(id) ?: throw AccessDeniedException("UserIntegrationConfig $id not visible")
        if (!guard.canModify(existing, auth)) {
            throw AccessDeniedException("UserIntegrationConfig $id not visible")
        }
        val merged = existing.copy(
            name = body.name,
            description = body.description,
            parameters = body.parameters,
        )
        return toResource(service.update(merged))
    }

    /**
     * DELETE /{id} — soft-delete a user-owned config.
     */
    @DeleteMapping("/{id}")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: UUID,
        auth: Authentication,
    ) {
        val existing = service.findById(id) ?: throw AccessDeniedException("UserIntegrationConfig $id not visible")
        if (!guard.canModify(existing, auth)) {
            throw AccessDeniedException("UserIntegrationConfig $id not visible")
        }
        service.delete(id)
    }

    internal fun toResource(entity: IntegrationConfig): UserIntegrationConfigResource =
        UserIntegrationConfigResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            name = entity.name,
            integrationType = entity.integrationType,
            description = entity.description,
            parameters = entity.parameters,
        )

    private fun currentUserId(auth: Authentication): UUID {
        val raw = auth.name ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication")
        return runCatching { UUID.fromString(raw) }
            .getOrElse {
                logger.warn { "[UserIntegrationConfigController] auth.name is not a UUID: '$raw'" }
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authentication identifier")
            }
    }

    private fun parseNamespaceFilter(raw: String?): NamespaceFilter = when {
        raw == null -> NamespaceFilter.Any
        raw.equals(NONE_SENTINEL, ignoreCase = true) -> NamespaceFilter.UserGlobalOnly
        else -> {
            val parsed = runCatching { UUID.fromString(raw) }
                .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid namespaceId: '$raw'") }
            NamespaceFilter.Specific(parsed)
        }
    }

    private sealed class NamespaceFilter {
        abstract fun accepts(namespaceId: UUID?): Boolean

        object Any : NamespaceFilter() {
            override fun accepts(namespaceId: UUID?): Boolean = true
        }

        object UserGlobalOnly : NamespaceFilter() {
            override fun accepts(namespaceId: UUID?): Boolean = namespaceId == null
        }

        data class Specific(val target: UUID) : NamespaceFilter() {
            override fun accepts(namespaceId: UUID?): Boolean = namespaceId == target
        }
    }

    companion object : KLogging() {
        const val NONE_SENTINEL = "none"
        const val MAX_PAGE_SIZE = 100
    }
}

/**
 * Pagination envelope for [UserIntegrationConfigController.list]. Kept narrow on purpose —
 * Spring Data's `Page<T>` would couple the API to a JPA-flavoured shape we do not need here.
 */
data class UserIntegrationConfigPage(
    val content: List<UserIntegrationConfigResource>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
