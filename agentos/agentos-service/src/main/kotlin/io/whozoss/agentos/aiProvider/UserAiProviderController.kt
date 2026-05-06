package io.whozoss.agentos.aiProvider

import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
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
 * REST API for user-scoped [AiProvider] entities — the personal LLM provider overrides
 * that allow a MEMBER to use their own API keys without admin intervention (Epic 6).
 *
 * **Why not extend `EntityController`** — authorization is ownership-based (`provider.userId == auth.name`),
 * not namespace-membership-based. Flat `@RestController` delegates authz to [UserAiProviderGuard].
 *
 * **Mass-assignment guard** (FR19, FR24, AR6, AR14) — `userId` from the request body is always
 * ignored. On create it is forced to `auth.name`; on update `userId`, `namespaceId`, `id`, `apiType`
 * are preserved from the persisted row via `existing.copy(...)`.
 *
 * **404 vs 403** (FR21, NFR-SEC-2, AR7) — read/update/delete endpoints annotated `@HideOnAccessDenied`
 * translate [AccessDeniedException] to 404, hiding existence. The create endpoint uses explicit 403
 * because the namespace is client-supplied (the caller knows it exists).
 */
@RestController
@RequestMapping("/api/user-ai-providers", produces = [MediaType.APPLICATION_JSON_VALUE])
class UserAiProviderController(
    private val service: AiProviderService,
    private val guard: UserAiProviderGuard,
) {

    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @Valid @RequestBody body: UserAiProviderResource,
        auth: Authentication,
    ): UserAiProviderResource {
        val me = currentUserId(auth)
        val target = AiProvider(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            namespaceId = body.namespaceId,
            userId = me,
            name = body.name,
            description = body.description,
            apiType = body.apiType!!,
            baseUrl = body.baseUrl,
            apiKey = body.apiKey,
        )
        if (!guard.canCreate(target, auth)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Cannot create user-ai-provider in namespace ${body.namespaceId} (READ permission required)",
            )
        }
        return toResource(service.create(target))
    }

    @GetMapping("/{id}")
    @HideOnAccessDenied
    fun getById(
        @PathVariable id: UUID,
        auth: Authentication,
    ): UserAiProviderResource {
        val cfg = service.findById(id) ?: throw AccessDeniedException("UserAiProvider $id not visible")
        if (!guard.canRead(cfg, auth)) {
            throw AccessDeniedException("UserAiProvider $id not visible")
        }
        return toResource(cfg)
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        auth: Authentication,
    ): UserAiProviderPage {
        val me = currentUserId(auth)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        val filter = parseNamespaceFilter(namespaceId)
        val all = service.findByUserId(me).filter { filter.accepts(it.namespaceId) }
        val total = all.size
        // Long arithmetic: safePage can be Int.MAX_VALUE → safePage*safeSize would overflow Int.
        val from = (safePage.toLong() * safeSize).coerceAtMost(total.toLong()).toInt()
        val to = min(from + safeSize, total)
        val pageItems = if (from >= to) emptyList() else all.subList(from, to)
        return UserAiProviderPage(
            content = pageItems.map { toResource(it) },
            page = safePage,
            size = safeSize,
            totalElements = total.toLong(),
            totalPages = ((total.toLong() + safeSize - 1) / safeSize).toInt(),
        )
    }

    @PutMapping("/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @HideOnAccessDenied
    fun update(
        @PathVariable id: UUID,
        @Valid @RequestBody body: UserAiProviderResource,
        auth: Authentication,
    ): UserAiProviderResource {
        val existing = service.findById(id) ?: throw AccessDeniedException("UserAiProvider $id not visible")
        if (!guard.canModify(existing, auth)) {
            throw AccessDeniedException("UserAiProvider $id not visible")
        }
        val merged = existing.copy(
            name = body.name,
            description = body.description,
            baseUrl = body.baseUrl,
            apiKey = resolveApiKey(body.apiKey, existing.apiKey),
        )
        return toResource(service.update(merged))
    }

    @DeleteMapping("/{id}")
    @HideOnAccessDenied
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable id: UUID,
        auth: Authentication,
    ) {
        val existing = service.findById(id) ?: throw AccessDeniedException("UserAiProvider $id not visible")
        if (!guard.canModify(existing, auth)) {
            throw AccessDeniedException("UserAiProvider $id not visible")
        }
        service.delete(id)
    }

    internal fun toResource(entity: AiProvider): UserAiProviderResource =
        UserAiProviderResource(
            id = entity.metadata.id,
            namespaceId = entity.namespaceId,
            userId = entity.userId,
            name = entity.name,
            description = entity.description,
            apiType = entity.apiType,
            baseUrl = entity.baseUrl,
            apiKey = maskApiKey(entity.apiKey),
        )

    private fun resolveApiKey(incoming: String?, current: String?): String? = when {
        isMasked(incoming) -> current
        incoming.isNullOrBlank() -> current
        else -> incoming
    }

    private fun currentUserId(auth: Authentication): UUID {
        val raw = auth.name ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication")
        return runCatching { UUID.fromString(raw) }
            .getOrElse {
                logger.warn { "[UserAiProviderController] auth.name is not a UUID: '$raw'" }
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

data class UserAiProviderPage(
    val content: List<UserAiProviderResource>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
