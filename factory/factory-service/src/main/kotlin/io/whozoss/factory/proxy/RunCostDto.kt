package io.whozoss.factory.proxy

/**
 * Local, boundary-faithful view of the AgentOS `RunCostDto`
 * (`GET /api/cases/{caseId}/run-cost`). factory-service does not depend on
 * agentos-sdk, so this is parsed from the JSON reply as plain fields.
 *
 * AgentOS aggregates the whole descendant tree of the queried case
 * (delegations included): asking for a root case already accounts for its
 * sub-cases.
 *
 * `cost` is a KNOWN lower bound; `unknownCostCount > 0` means some turns could
 * not be priced and the real cost is strictly higher — an unknown cost must
 * never be treated as 0.
 */
data class RunCostDto(
    val caseId: String,
    val cost: Double,
    val unknownCostCount: Long,
    val runCostThreshold: Double?,
    val paused: Boolean,
    val active: Boolean,
    val liveTokens: Long,
    /** Case ids currently held at a threshold; this case or one of its blocking ancestors. */
    val pausedCaseIds: List<String> = emptyList(),
)
