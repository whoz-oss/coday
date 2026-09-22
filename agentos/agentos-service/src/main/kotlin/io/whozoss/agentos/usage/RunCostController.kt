package io.whozoss.agentos.usage

import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.sdk.api.usageRecord.ContinueCostRequest
import io.whozoss.agentos.sdk.api.usageRecord.RunCostApi
import io.whozoss.agentos.sdk.api.usageRecord.RunCostDto
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

@RestController
@RequestMapping("/api/cases/{caseId}/run-cost", produces = [MediaType.APPLICATION_JSON_VALUE])
class RunCostController(
    private val costs: RunCostService,
    private val cases: CaseService,
) : RunCostApi {
    @GetMapping
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    override fun getRunCost(
        @PathVariable caseId: UUID,
    ): RunCostDto = costs.state(caseId)

    @PostMapping("/continue", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun continueCostRun(
        @PathVariable caseId: UUID,
        @RequestBody request: ContinueCostRequest,
    ): RunCostDto = costs.continueRun(caseId, request.expectedThreshold)

    @PostMapping("/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(#caseId, 'Case', 'WRITE')")
    override fun stopCostRun(
        @PathVariable caseId: UUID,
    ) {
        cases.interruptCase(caseId)
    }
}
