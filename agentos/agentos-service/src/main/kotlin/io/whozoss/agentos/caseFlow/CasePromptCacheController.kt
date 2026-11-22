package io.whozoss.agentos.caseFlow

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.chat.PromptCacheStatus
import io.whozoss.agentos.chat.PromptCacheStatusService
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST entry point of the prompt cache status of a case, shown in its usage bar. READ on the case, as for its usage.
 *
 * A standalone controller, not part of `CaseApi`, for the same reason as [CaseSideQuestionController].
 */
@RestController
class CasePromptCacheController(
    private val promptCacheStatusService: PromptCacheStatusService,
) {
    @Operation(
        summary = "Whether the prompt cache of a case is warm or cold",
        description =
            "GET /api/cases/{caseId}/prompt-cache: when the provider's prompt cache of the case's last request " +
                "expires, and what writing its prompt to the cache again would cost. Both null when unknown.",
    )
    @GetMapping("/api/cases/{caseId}/prompt-cache", produces = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    fun getPromptCacheStatus(
        @PathVariable caseId: UUID,
    ): PromptCacheStatus = promptCacheStatusService.statusOf(caseId)
}
