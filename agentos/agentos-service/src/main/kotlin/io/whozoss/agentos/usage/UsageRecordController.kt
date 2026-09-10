package io.whozoss.agentos.usage

import io.whozoss.agentos.sdk.api.usageRecord.UsageAggregateByKeyDto
import io.whozoss.agentos.sdk.api.usageRecord.UsageAggregateDto
import io.whozoss.agentos.sdk.api.usageRecord.UsageRecordApi
import io.whozoss.agentos.sdk.api.usageRecord.UsageRecordDto
import mu.KLogging
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * REST API for [UsageRecord] entities. Implements [UsageRecordApi] so external consumers
 * can declare a Feign client against the SDK interface.
 *
 * Usage records are write-once analytical facts created internally by the agent execution
 * pipeline. This controller exposes read and aggregation endpoints only.
 *
 * ## Authorization
 *
 * Per-case endpoints — require Case `READ`:
 * - [listByCaseId], [aggregateByCaseId], [aggregateByCaseTree]
 * - Case READ follows the owner-private rule (FR15): direct MEMBER/ADMIN on the case,
 *   or transitive via namespace ADMIN. Namespace MEMBERs without a direct case relation
 *   are denied — they cannot see another user's cost data.
 *
 * Namespace-scoped aggregation endpoints — require Namespace `WRITE` (= ADMIN):
 * - [aggregateByUser], [aggregateByAgent], [aggregateByModel]
 * - These cross-user analytics are intentionally restricted to namespace admins and
 *   super-admins. A namespace MEMBER must not see aggregated costs across cases they
 *   do not individually have access to.
 */
@RestController
@RequestMapping("/api/usage-records", produces = [MediaType.APPLICATION_JSON_VALUE])
class UsageRecordController(
    private val usageRecordService: UsageRecordService,
) : UsageRecordApi {

    @GetMapping("/by-case/{caseId}")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    override fun listByCaseId(@PathVariable caseId: UUID): List<UsageRecordDto> =
        usageRecordService.findByCaseId(caseId).map { it.toDto() }

    @GetMapping("/aggregate/by-case/{caseId}")
    @PreAuthorize("hasPermission(#caseId, 'Case', 'READ')")
    override fun aggregateByCaseId(@PathVariable caseId: UUID): UsageAggregateDto =
        usageRecordService.aggregateByCaseId(caseId).toDto()

    @GetMapping("/aggregate/by-case-tree/{rootCaseId}")
    @PreAuthorize("hasPermission(#rootCaseId, 'Case', 'READ')")
    override fun aggregateByCaseTree(@PathVariable rootCaseId: UUID): UsageAggregateDto =
        usageRecordService.aggregateByCaseTree(rootCaseId).toDto()

    @GetMapping("/aggregate/by-user")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    override fun aggregateByUser(
        @RequestParam userId: UUID,
        @RequestParam namespaceId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant,
    ): UsageAggregateDto =
        usageRecordService.aggregateByUser(userId, namespaceId, from, to).toDto()

    @GetMapping("/aggregate/by-agent")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    override fun aggregateByAgent(
        @RequestParam namespaceId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant,
    ): List<UsageAggregateByKeyDto> =
        usageRecordService.aggregateByAgent(namespaceId, from, to).map { it.toDto() }

    @GetMapping("/aggregate/by-model")
    @PreAuthorize("hasPermission(#namespaceId, 'Namespace', 'WRITE')")
    override fun aggregateByModel(
        @RequestParam namespaceId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant,
    ): List<UsageAggregateByKeyDto> =
        usageRecordService.aggregateByModel(namespaceId, from, to).map { it.toDto() }

    companion object : KLogging()
}

// ---------------------------------------------------------------------------
// Extension: domain → DTO
// ---------------------------------------------------------------------------

private fun UsageRecord.toDto() =
    UsageRecordDto(
        id = metadata.id,
        namespaceId = namespaceId,
        caseId = caseId,
        userId = userId,
        source = source.name,
        outcome = outcome.name,
        agentConfigId = agentConfigId,
        agentName = agentName,
        providerName = providerName,
        apiModelName = apiModelName,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        totalTokens = totalTokens,
        cost = cost,
        timestamp = timestamp,
        createdOn = metadata.created,
    )

private fun UsageAggregate.toDto() =
    UsageAggregateDto(
        recordCount = recordCount,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        totalTokens = totalTokens,
        cost = cost,
    )

private fun UsageAggregateByKey.toDto() =
    UsageAggregateByKeyDto(
        key = key,
        aggregate = aggregate.toDto(),
    )
