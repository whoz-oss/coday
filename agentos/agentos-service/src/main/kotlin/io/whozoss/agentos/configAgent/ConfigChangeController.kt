package io.whozoss.agentos.configAgent

import io.swagger.v3.oas.annotations.Operation
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeApi
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeApplyRequest
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeRejectRequest
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeReportDto
import io.whozoss.agentos.sdk.api.configChange.ConfigChangeViewDto
import io.whozoss.agentos.security.declarative.HideOnAccessDenied
import io.whozoss.agentos.user.UserService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The card's side of the config agent (#1477): shows a frozen plan, and applies or rejects it.
 *
 * Applying runs the plan's writes in this call, through the REST controllers, so every write is
 * authorized with the identity of the user who clicked, exactly like the same calls made by the
 * browser. The card calls these endpoints over HTTP; a messaging chat calls them in process, with the
 * linked user authenticated ([io.whozoss.agentos.messaging.MessagingGateway]). These handlers are
 * neither `suspend` nor `@Transactional` on purpose: the identity must stay on this thread, and each
 * controller commits its own writes before the answer resumes the agent.
 *
 * Only the user the plan was proposed to may apply or reject it (403 otherwise); anyone who can read
 * the case may view it, with the current values shown according to their own rights.
 *
 * Implements [ConfigChangeApi], the SDK contract, so external consumers can declare a Feign client
 * against it. All Spring annotations live here, none on the interface.
 */
@RestController
@RequestMapping("/api/cases/{caseId}/config-changes", produces = [MediaType.APPLICATION_JSON_VALUE])
class ConfigChangeController(
    private val configChangeService: ConfigChangeService,
    private val userService: UserService,
) : ConfigChangeApi {
    @Operation(summary = "View a configuration plan proposed by an agent, with the live status of each operation")
    @GetMapping("/{questionId}")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    @HideOnAccessDenied
    override fun viewConfigChange(
        @PathVariable caseId: UUID,
        @PathVariable questionId: UUID,
    ): ConfigChangeViewDto = configChangeService.view(caseId, questionId, userService.getCurrentUser())

    @Operation(summary = "Apply a configuration plan with the rights of the calling user, then answer the agent")
    @PostMapping("/{questionId}/apply", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun applyConfigChange(
        @PathVariable caseId: UUID,
        @PathVariable questionId: UUID,
        @RequestBody request: ConfigChangeApplyRequest,
    ): ConfigChangeReportDto = configChangeService.apply(caseId, questionId, userService.getCurrentUser(), request)

    @Operation(summary = "Reject a configuration plan, then answer the agent")
    @PostMapping("/{questionId}/reject")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    override fun rejectConfigChange(
        @PathVariable caseId: UUID,
        @PathVariable questionId: UUID,
        @RequestBody(required = false) request: ConfigChangeRejectRequest?,
    ) {
        configChangeService.reject(caseId, questionId, userService.getCurrentUser(), request?.comment)
    }
}
