package io.whozoss.agentos.usage

import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.api.usageRecord.CumulativeUsageDto
import io.whozoss.agentos.user.UserService
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class CumulativeUsageController(
    private val costs: RunCostService,
    private val permissions: PermissionService,
    private val users: UserService,
) {
    /** Read-only batch: authorize every requested root before revealing any usage or ancestry. */
    @GetMapping("/api/usage-records/aggregate/case-trees", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun cumulativeUsage(@RequestParam caseIds: List<UUID>): CumulativeUsageDto {
        if (caseIds.size !in 1..200) {
            throw org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "Supply between 1 and 200 case ids",
            )
        }
        val user = users.getCurrentUser()
        if (!user.isAdmin && caseIds.any {
                !permissions.hasPermission(user.id.toString(), EntityType.CASE, it.toString(), Action.READ)
            }) {
            throw AccessDeniedException("Case read permission required for every requested case")
        }
        return costs.cumulativeUsage(caseIds)
    }
}
