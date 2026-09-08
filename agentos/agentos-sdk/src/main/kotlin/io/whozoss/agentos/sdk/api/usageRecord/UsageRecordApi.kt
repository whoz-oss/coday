package io.whozoss.agentos.sdk.api.usageRecord

import java.time.Instant
import java.util.UUID

/**
 * HTTP API contract for [io.whozoss.agentos.usage.UsageRecord] entities.
 *
 * Implemented by `UsageRecordController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations.
 *
 * Usage records are write-once analytical facts: creation is internal (triggered by
 * the agent execution pipeline). The API exposes read and aggregation endpoints only.
 */
interface UsageRecordApi {

    /** GET /api/usage-records/{id} — fetch a single record by id. */
    fun getById(id: UUID): UsageRecordDto

    /** GET /api/usage-records/by-case/{caseId} — list all records for a case. */
    fun listByCaseId(caseId: UUID): List<UsageRecordDto>

    /** GET /api/usage-records/aggregate/by-case/{caseId} — aggregate for a single case. */
    fun aggregateByCaseId(caseId: UUID): UsageAggregateDto

    /** GET /api/usage-records/aggregate/by-case-tree/{rootCaseId} — aggregate for a case and all its descendants. */
    fun aggregateByCaseTree(rootCaseId: UUID): UsageAggregateDto

    /**
     * GET /api/usage-records/aggregate/by-user — aggregate for a user in a namespace over a time window.
     *
     * Query params: `userId`, `namespaceId`, `from`, `to` (ISO-8601 instants).
     */
    fun aggregateByUser(
        userId: UUID,
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): UsageAggregateDto

    /**
     * GET /api/usage-records/aggregate/by-agent — aggregate grouped by agent in a namespace over a time window.
     *
     * Query params: `namespaceId`, `from`, `to` (ISO-8601 instants).
     */
    fun aggregateByAgent(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKeyDto>

    /**
     * GET /api/usage-records/aggregate/by-model — aggregate grouped by model in a namespace over a time window.
     *
     * Query params: `namespaceId`, `from`, `to` (ISO-8601 instants).
     */
    fun aggregateByModel(
        namespaceId: UUID,
        from: Instant,
        to: Instant,
    ): List<UsageAggregateByKeyDto>
}
