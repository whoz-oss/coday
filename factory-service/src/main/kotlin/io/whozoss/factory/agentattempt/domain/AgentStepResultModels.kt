package io.whozoss.factory.agentattempt.domain

import io.whozoss.factory.error.FactoryException

/**
 * The two business result statuses a worker may submit.
 *
 * Port of `AGENT_STEP_RESULT_STATUSES` in
 * `factory/src/domain/agent-attempt/agent-step-result.ts`.
 */
enum class AgentStepResultStatus {
    PASS,
    FAIL,
    ;

    companion object {
        fun fromWire(value: String?): AgentStepResultStatus? = entries.firstOrNull { it.name == value }
    }
}

/**
 * Bounded limits of the structured business result schema.
 *
 * Port of `AGENT_STEP_RESULT_LIMITS`. The limits are enforced by
 * [AgentStepResultValidation].
 */
object AgentStepResultLimits {
    const val SUMMARY = 2000
    const val MODIFIED_FILES = 1000
    const val MODIFIED_FILE_LENGTH = 1024
    const val ARTIFACTS = 8
    const val ARTIFACT_KIND = 128
    const val ARTIFACT_CONTENT_BYTES = 262144
    const val FINDINGS = 100
    const val FINDING_CODE = 128
    const val FINDING_SUMMARY = 1000
    const val FINDING_FILE = 1024

    /** Default capability lifetime: the Node `DEFAULT_TTL_MS` (15 minutes). */
    const val CAPABILITY_TTL_SECONDS = 15L * 60L

    /** Submission budget of a capability (single-use, port of `submissionBudget: 1`). */
    const val SUBMISSION_BUDGET = 1
}

/** Allowed finding severities (`info` / `warning` / `error` / `blocking`). */
enum class AgentStepResultSeverity(val wire: String) {
    INFO("info"),
    WARNING("warning"),
    ERROR("error"),
    BLOCKING("blocking"),
    ;

    companion object {
        fun fromWire(value: String?): AgentStepResultSeverity? = entries.firstOrNull { it.wire == value }
    }
}

/** One structured artifact attached to a business result. */
data class AgentStepResultArtifact(
    val kind: String,
    val encoding: String,
    val content: String,
)

/** The `claims` sub-object of a business result. */
data class AgentStepResultClaim(
    val modifiedFiles: List<String>,
)

/** One structured finding attached to a business result. */
data class AgentStepResultFinding(
    val severity: String,
    val code: String,
    val summary: String,
    val file: String? = null,
    val line: Int? = null,
)

/** The validated business result a worker submits for a step attempt. */
data class AgentStepResultBusiness(
    val status: AgentStepResultStatus,
    val summary: String,
    val claims: AgentStepResultClaim,
    val artifacts: List<AgentStepResultArtifact> = emptyList(),
    val findings: List<AgentStepResultFinding> = emptyList(),
)

/** Identity a result store issues a submission capability for. */
data class AgentStepResultCapabilityIdentity(
    val attemptId: String,
    val workflowId: String,
    val stepId: String,
    val namespaceId: String,
    val caseId: String,
    val agentName: String,
    val briefHash: String,
)

/**
 * The identity a submission declares, checked against the issued capability.
 *
 * [namespaceId] is NEVER taken from model-authored arguments: the HTTP
 * boundary fills it from the verified `TrustContext` (signed JWT claims, or
 * the loopback-dev headers on a local socket). When present it must equal the
 * capability namespace, otherwise the submission is rejected with
 * `RESULT_IDENTITY_MISMATCH`.
 */
data class AgentStepResultObservedIdentity(
    val attemptId: String?,
    val caseId: String?,
    val agentName: String?,
    val namespaceId: String? = null,
)

/** Durable capability-issued record (the clear token is never persisted). */
data class AgentStepResultCapability(
    val type: String,
    val capabilityId: String,
    val tokenHash: String,
    val attemptId: String,
    val workflowId: String,
    val stepId: String,
    val namespaceId: String,
    val caseId: String,
    val agentName: String,
    val briefHash: String,
    val issuedAt: String,
    val expiresAt: String,
    val submissionBudget: Int,
)

/** A freshly issued submission capability (the clear token is returned once). */
data class IssuedCapability(
    val token: String,
    val expiresAt: String,
)

/** Durable result-submitted record. */
data class AgentStepResultSubmitted(
    val type: String,
    val resultId: String,
    val attemptId: String,
    val workflowId: String,
    val stepId: String,
    val namespaceId: String,
    val caseId: String,
    val agentName: String,
    val briefHash: String,
    val status: AgentStepResultStatus,
    val summary: String,
    val artifacts: List<AgentStepResultArtifact>,
    val claims: AgentStepResultClaim,
    val findings: List<AgentStepResultFinding>,
    val submittedAt: String,
    val resultHash: String,
)

/** Stored attempt row projection. */
data class AgentStepAttemptRecord(
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val attemptId: String,
    val agentId: String,
    val status: String,
    val revision: Int,
    val payload: String,
)

/** Stored result row projection (a reservation or a submitted result). */
data class AgentStepResultRow(
    val namespaceId: String,
    val workflowId: String,
    val stepId: String,
    val attemptId: String,
    val resultId: String,
    val resultStatus: String,
    val semanticSignature: String?,
    val payload: String,
)

/** Outcome of a capability-backed business result submission. */
sealed interface SubmitOutcome {
    val result: AgentStepResultSubmitted
    val idempotent: Boolean

    /** A newly created result. */
    data class Created(override val result: AgentStepResultSubmitted) : SubmitOutcome {
        override val idempotent: Boolean = false
    }

    /** An idempotent replay of an identical result hash. */
    data class Replayed(override val result: AgentStepResultSubmitted) : SubmitOutcome {
        override val idempotent: Boolean = true
    }
}

/**
 * Machine-readable error codes of the AGENT-STEP aggregate.
 *
 * Vocabulary mirrors the Node control plane
 * (`factory/src/domain/agent-attempt/agent-step-result.ts` and
 * `factory/dashboard/agent-step-result-routes.mjs`) so a client keeps the exact
 * same error semantics across the Node and Kotlin control planes.
 */
object AgentAttemptErrorCodes {
    const val RESULT_SCHEMA_INVALID = "RESULT_SCHEMA_INVALID"
    const val RESULT_CAPABILITY_INVALID = "RESULT_CAPABILITY_INVALID"
    const val RESULT_IDENTITY_MISMATCH = "RESULT_IDENTITY_MISMATCH"
    const val RESULT_CAPABILITY_EXPIRED = "RESULT_CAPABILITY_EXPIRED"
    const val RESULT_SEMANTIC_COLLISION = "RESULT_SEMANTIC_COLLISION"
    const val IDEMPOTENCY_KEY_COLLISION = "IDEMPOTENCY_KEY_COLLISION"
    const val RESULT_CAPABILITY_ALREADY_ISSUED = "RESULT_CAPABILITY_ALREADY_ISSUED"
    const val RESULT_CAPABILITY_IDENTITY_CONFLICT = "RESULT_CAPABILITY_IDENTITY_CONFLICT"
    const val INVALID_RESULT_CAPABILITY_IDENTITY = "INVALID_RESULT_CAPABILITY_IDENTITY"
    const val INVALID_RESULT_REQUEST = "INVALID_RESULT_REQUEST"
    const val TRUST_CONTEXT_UNAVAILABLE = "TRUST_CONTEXT_UNAVAILABLE"
}

/**
 * Base of every AGENT-STEP failure carrying a stable machine code.
 *
 * Extends [FactoryException] so the canonical
 * `{ "error": { code, message, details } }` envelope is rendered by the shared
 * `FactoryExceptionHandler`.
 */
open class AgentAttemptException(
    errorCode: String,
    statusCode: Int,
    message: String = errorCode,
    details: Any? = null,
    cause: Throwable? = null,
) : FactoryException(statusCode, errorCode, message, details, cause)

/** 400 — the structured business result fails validation. */
class ResultSchemaInvalidException(
    message: String = "The structured business result is invalid",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.RESULT_SCHEMA_INVALID, 400, message, details)

/** 401 — the bearer capability token is unknown. */
class ResultCapabilityInvalidException(
    message: String = "Unknown result submission capability",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.RESULT_CAPABILITY_INVALID, 401, message, details)

/** 400 — the observed identity does not match the issued capability. */
class ResultIdentityMismatchException(
    message: String = "Observed identity does not match the issued capability",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.RESULT_IDENTITY_MISMATCH, 400, message, details)

/** 410 — the capability is expired. */
class ResultCapabilityExpiredException(
    message: String = "The result submission capability has expired",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.RESULT_CAPABILITY_EXPIRED, 410, message, details)

/** 409 — the attempt already submitted a different result. */
class ResultSemanticCollisionException(
    message: String = "A different result was already submitted for this attempt",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.RESULT_SEMANTIC_COLLISION, 409, message, details)

/** 409 — an idempotency key was replayed with a divergent request hash. */
class IdempotencyKeyCollisionException(
    message: String = "The idempotency key was already used with a different request",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.IDEMPOTENCY_KEY_COLLISION, 409, message, details)

/** 409 — a capability was already issued for the attempt identity. */
class ResultCapabilityAlreadyIssuedException(
    message: String = "A capability was already issued for this attempt",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.RESULT_CAPABILITY_ALREADY_ISSUED, 409, message, details)

/** 409 — a capability exists for the attempt with a different identity. */
class ResultCapabilityIdentityConflictException(
    message: String = "A capability already exists for this attempt with a different identity",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.RESULT_CAPABILITY_IDENTITY_CONFLICT, 409, message, details)

/** 400 — the capability identity to issue is malformed. */
class InvalidResultCapabilityIdentityException(
    message: String = "The result capability identity is invalid",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.INVALID_RESULT_CAPABILITY_IDENTITY, 400, message, details)

/** 400 — the result request is structurally invalid. */
class InvalidResultRequestException(
    message: String = "Invalid result request",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.INVALID_RESULT_REQUEST, 400, message, details)

/** 401 — no verified trust context could be resolved for the caller. */
class TrustContextUnavailableException(
    message: String = "A verified trust context is required",
    details: Any? = null,
) : AgentAttemptException(AgentAttemptErrorCodes.TRUST_CONTEXT_UNAVAILABLE, 401, message, details)
