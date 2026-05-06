package io.whozoss.agentos.aiModel

import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.aiProvider.AiModel
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
 * REST API for user-scoped [AiModel] entities — personal LLM model overrides (Epic 6).
 *
 * A `UserAiModel` must be attached to a `UserAiProvider` (parent must have `userId != null`
 * and `userId == auth.name`). [UserAiModelGuard.canCreateVerdict] distinguishes three failure
 * modes: parent missing (404), parent namespace-only (403), parent cross-user (404).
 *
 * [AiModelServiceImpl.create] automatically denormalises `namespaceId` and `userId` from
 * the parent provider — the controller leaves them null in the target entity.
 *
 * Mass-assignment guard: `id`, `aiProviderId`, `namespaceId`, `userId` are always preserved
 * from the persisted row via `existing.copy(...)` on update.
 */
@RestController
@RequestMapping("/api/user-ai-models", produces = [MediaType.APPLICATION_JSON_VALUE])
class UserAiModelController(
    private val service: AiModelService,
    private val guard: UserAiModelGuard,
) {

    /**
     * POST — create a user-scoped AiModel.
     *
     * @HideOnAccessDenied is present because [UserAiModelGuard.CreateVerdict.ParentMissing] and
     * [UserAiModelGuard.CreateVerdict.CrossUser] both surface as [AccessDeniedException] → 404.
     * [UserAiModelGuard.CreateVerdict.ParentNotUserScoped] is a [ResponseStatusException] → 403 (explicit).
     */
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    @HideOnAccessDenied
    fun create(
        @Valid @RequestBody body: UserAiModelResource,
        auth: Authentication,
    ): UserAiModelResource {
        // Validate auth.name is a UUID up front (401 on malformed) — symmetric with
        // UserAiProviderController.create. Without this, a malformed auth.name would
        // fall through the guard's string comparison and surface as 404, hiding the
        // real auth issue from the caller.
        currentUserId(auth)
        // Defence-in-depth against a regression dropping @Valid on the body: surface a
        // descriptive 400 instead of a 500 NPE if `aiProviderId` ever reaches here as null.
        val aiProviderId = body.aiProviderId
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "aiProviderId is required")
        val target = AiModel(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            aiProviderId = aiProviderId,
            namespaceId = null,
            userId = null,
            apiModelName = body.apiModelName,
            description = body.description,
            alias = body.alias,
            priority = body.priority,
            temperature = body.temperature,
            maxTokens = body.maxTokens,
        )
        when (guard.canCreateVerdict(target, auth)) {
            is UserAiModelGuard.CreateVerdict.Ok -> { /* proceed */ }
            is UserAiModelGuard.CreateVerdict.ParentMissing ->
                throw AccessDeniedException("Parent AiProvider not visible")
            is UserAiModelGuard.CreateVerdict.CrossUser ->
                throw AccessDeniedException("Parent AiProvider not visible")
            is UserAiModelGuard.CreateVerdict.ParentNotUserScoped ->
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "Parent must be a UserAiProvider")
        }
        // Parent may be soft-deleted between the guard check and service.create —
        // surface the same 404 as ParentMissing instead of leaking a generic 404 message.
        val created = try {
            service.create(target)
        } catch (e: ResourceNotFoundException) {
            throw AccessDeniedException("Parent AiProvider not visible", e)
        }
        return toResource(created)
    }

    @GetMapping("/{id}")
    @HideOnAccessDenied
    fun getById(
        @PathVariable id: UUID,
        auth: Authentication,
    ): UserAiModelResource {
        val model = service.findById(id) ?: throw AccessDeniedException("UserAiModel $id not visible")
        if (!guard.canRead(model, auth)) {
            throw AccessDeniedException("UserAiModel $id not visible")
        }
        return toResource(model)
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    fun list(
        @RequestParam(required = false) namespaceId: String?,
        @RequestParam(required = false) aiProviderId: UUID?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        auth: Authentication,
    ): UserAiModelPage {
        val me = currentUserId(auth)
        val safeSize = size.coerceIn(1, MAX_PAGE_SIZE)
        val safePage = page.coerceAtLeast(0)
        val nsFilter = parseNamespaceFilter(namespaceId)
        val all = service.findByUserId(me)
            .filter { nsFilter.accepts(it.namespaceId) }
            .filter { aiProviderId == null || it.aiProviderId == aiProviderId }
        val total = all.size
        // Long arithmetic: safePage can be Int.MAX_VALUE → safePage*safeSize would overflow Int.
        val from = (safePage.toLong() * safeSize).coerceAtMost(total.toLong()).toInt()
        val to = min(from + safeSize, total)
        val pageItems = if (from >= to) emptyList() else all.subList(from, to)
        return UserAiModelPage(
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
        @Valid @RequestBody body: UserAiModelResource,
        auth: Authentication,
    ): UserAiModelResource {
        val existing = service.findById(id) ?: throw AccessDeniedException("UserAiModel $id not visible")
        if (!guard.canModify(existing, auth)) {
            throw AccessDeniedException("UserAiModel $id not visible")
        }
        val merged = existing.copy(
            apiModelName = body.apiModelName,
            description = body.description,
            alias = body.alias,
            priority = body.priority,
            temperature = body.temperature,
            maxTokens = body.maxTokens,
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
        val existing = service.findById(id) ?: throw AccessDeniedException("UserAiModel $id not visible")
        if (!guard.canModify(existing, auth)) {
            throw AccessDeniedException("UserAiModel $id not visible")
        }
        service.delete(id)
    }

    internal fun toResource(entity: AiModel): UserAiModelResource =
        UserAiModelResource(
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

    private fun currentUserId(auth: Authentication): UUID {
        val raw = auth.name ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication")
        return runCatching { UUID.fromString(raw) }
            .getOrElse {
                logger.warn { "[UserAiModelController] auth.name is not a UUID: '$raw'" }
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

data class UserAiModelPage(
    val content: List<UserAiModelResource>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)
