package io.whozoss.factory.planchange.domain

import io.whozoss.factory.web.factoryError

/**
 * Stable plan-change error codes (Phase 8 governed replanning).
 *
 * Every failure is thrown as a `FactoryHttpException` via [factoryError], so the
 * `FactoryExceptionHandler` renders the canonical
 * `{ "error": { code, message, details } }` envelope without any controller
 * try/catch. Codes are stable and must not be renamed without versioning.
 */

/** 400 — malformed body, unknown field, exceeded bound or incoherent payload shape. */
fun invalidPlanChangeProposal(message: String, details: Any? = null): Nothing =
    factoryError(400, "INVALID_PLAN_CHANGE_PROPOSAL", message, details)

/** 400 — missing/blank required query parameter or unknown status filter. */
fun invalidPlanChangeQuery(message: String, details: Any? = null): Nothing =
    factoryError(400, "INVALID_PLAN_CHANGE_QUERY", message, details)

/** 400 — unknown or undeliverable decision value in a `/decide` body. */
fun invalidPlanChangeDecision(message: String, details: Any? = null): Nothing =
    factoryError(400, "INVALID_PLAN_CHANGE_DECISION", message, details)

/** 400 — missing/blank namespace. */
fun invalidPlanChangeNamespace(message: String = "namespaceId is required", details: Any? = null): Nothing =
    factoryError(400, "INVALID_NAMESPACE_ID", message, details)

/** 404 — the proposal is unknown within the caller's tenant scope. */
fun planChangeProposalNotFound(proposalId: String): Nothing =
    factoryError(
        404,
        "PLAN_CHANGE_PROPOSAL_NOT_FOUND",
        "Plan change proposal '$proposalId' not found",
        mapOf("proposalId" to proposalId),
    )

/** 409 — same idempotency tuple, different canonical payload. */
fun planChangeIdempotencyCollision(workflowId: String, idempotencyKey: String): Nothing =
    factoryError(
        409,
        "IDEMPOTENCY_KEY_COLLISION",
        "Idempotency key '$idempotencyKey' was already used on workflow '$workflowId' with a different payload",
        mapOf("workflowId" to workflowId, "idempotencyKey" to idempotencyKey),
    )

/**
 * 409 — the requested decision violates the governance Rules 1–3 (e.g.
 * auto-applying a structural / contract / scope / DAG change).
 */
fun planChangeGateRequired(kind: PlanChangeKind, requested: PlanChangeDecisionStatus): Nothing =
    factoryError(
        409,
        "PLAN_CHANGE_GATE_REQUIRED",
        "Decision '${requested.dbValue}' is not allowed for a '${kind.dbValue}' proposal: " +
            "structural, contract, oracle, scope or DAG changes require a human governance gate " +
            "or a new workflow definition projection and never rewrite active instances silently",
        mapOf("kind" to kind.dbValue, "requestedDecision" to requested.dbValue),
    )
