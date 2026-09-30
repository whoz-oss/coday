package io.whozoss.agentos.usage

import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.usageRecord.ContinueCostRequest
import io.whozoss.agentos.sdk.api.usageRecord.RunCostApi
import io.whozoss.agentos.sdk.api.usageRecord.RunCostDto
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
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/cases/{caseId}/run-cost", produces = [MediaType.APPLICATION_JSON_VALUE])
class RunCostController(
    private val costs: RunCostService,
    private val cases: CaseService,
    private val permissions: PermissionService,
    private val users: UserService,
    private val usageConfig: UsageConfigProperties = UsageConfigProperties(),
) : RunCostApi {
    @GetMapping
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    override fun getRunCost(
        @PathVariable caseId: UUID,
    ): RunCostDto = withCallerPermissions(costs.state(caseId))

    @PostMapping("/continue", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun continueCostRun(
        @PathVariable caseId: UUID,
        @RequestBody request: ContinueCostRequest,
    ): RunCostDto = withCallerPermissions(costs.continueRun(caseId, request.expectedThreshold))

    @PostMapping("/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun stopCostRun(
        @PathVariable caseId: UUID,
    ) {
        if (!usageConfig.enabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Usage tracking is disabled")
        }
        cases.interruptCase(caseId)
    }

    private fun withCallerPermissions(state: RunCostDto): RunCostDto {
        if (state.pausedCases.isEmpty()) return state
        val user = users.getCurrentUser()
        val ids = state.pausedCases.map { it.caseId.toString() }.toSet()
        val readable = if (user.isAdmin) ids else {
            ids.filter { permissions.hasPermission(user.id.toString(), EntityType.CASE, it, Action.READ) }.toSet()
        }
        val writable = if (user.isAdmin) readable else {
            permissions.filterVisibleIds(user.id.toString(), EntityType.CASE, readable, Action.WRITE)
        }
        // Preserve the effective pause even when its ancestor's details are not readable.
        return state.copy(
            pausedCases = state.pausedCases.filter { it.caseId.toString() in readable }.map {
                it.copy(canContinue = it.caseId.toString() in writable)
            },
        )
    }
}
