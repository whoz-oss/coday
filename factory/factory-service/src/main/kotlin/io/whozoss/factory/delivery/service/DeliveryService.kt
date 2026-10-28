package io.whozoss.factory.delivery.service

import io.whozoss.factory.delivery.config.DeliveryProperties
import io.whozoss.factory.delivery.domain.CanonicalHash
import io.whozoss.factory.delivery.domain.DeliveryDefinitionValidation
import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import io.whozoss.factory.delivery.domain.DeliveryExecutionContext
import io.whozoss.factory.delivery.domain.DeliveryPromotionRequestValidation
import io.whozoss.factory.delivery.domain.HashedDeliveryDefinition
import io.whozoss.factory.delivery.domain.deliveryException
import io.whozoss.factory.delivery.domain.defaultDeliveryDefinition
import io.whozoss.factory.delivery.domain.hashDeliveryDefinition
import io.whozoss.factory.delivery.domain.nowIso
import io.whozoss.factory.delivery.domain.validateDeliveryDefinition
import io.whozoss.factory.delivery.domain.validateDeliveryPromotionRequest
import io.whozoss.factory.delivery.persistence.DeliveryRepository
import io.whozoss.factory.delivery.persistence.DeliveryStorePromoteInput
import io.whozoss.factory.delivery.persistence.DeliveryWriteResult
import io.whozoss.factory.delivery.port.DeliveryEvidenceStore
import io.whozoss.factory.delivery.port.DeliveryGitControlPlane
import io.whozoss.factory.delivery.port.DeliveryGitPushResult
import io.whozoss.factory.delivery.port.DeliveryGitWorktreeBinding
import io.whozoss.factory.delivery.port.DeliveryPullRequestAdapter
import io.whozoss.factory.delivery.port.DeliveryPullRequestContext
import io.whozoss.factory.delivery.port.DeliveryPullRequestResult
import io.whozoss.factory.environment.domain.WorkEnvironment
import io.whozoss.factory.environment.domain.WorkEnvironmentState
import io.whozoss.factory.environment.persistence.WorkEnvironmentRepository
import io.whozoss.factory.environment.port.GitProvisioner
import io.whozoss.factory.environment.port.GitReconciliation
import io.whozoss.factory.persistence.TenantScope
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** A resolved delivery: its durable snapshot bound to the trusted environment. */
data class DeliveryResolution(
    val snapshot: Map<String, Any?>,
    val environment: WorkEnvironment,
    val headCommit: String,
)

/** HTTP-shaped result of a delivery operation: status + data payload. */
data class DeliveryHttpResult(
    val status: Int,
    val data: Any?,
)

private val DELIVERY_UUID =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val DELIVERY_SAFE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val FORBIDDEN_BODY_FIELDS = setOf(
    "root", "repoRoot", "worktreePath", "remote", "url", "owner", "repo", "command", "credentials", "token",
)

/**
 * Application service of the DELIVERY aggregate.
 *
 * Port of `factory/src/application/delivery/delivery-controller.ts`: binds a
 * delivery to its controlling execution (namespace, workflow, parent case,
 * dashboard runtime) and its environment (worktree, branch, commits), drives git
 * checkpoints / pushes / pull requests through the injected adapters, journals
 * every outcome and applies the ordered, evidence-gated promotion policy.
 */
@Service
class DeliveryService(
    private val repository: DeliveryRepository,
    private val evidenceStore: DeliveryEvidenceStore,
    private val environmentRepository: WorkEnvironmentRepository,
    private val gitProvisioner: GitProvisioner,
    private val git: DeliveryGitControlPlane,
    private val pullRequests: DeliveryPullRequestAdapter,
    private val properties: DeliveryProperties,
) {

    /** The validated, hashed default delivery definition. */
    val definition: HashedDeliveryDefinition = buildDefinition()

    /** Resolves (and lazily creates) the delivery bound to [workflowId]. */
    @Transactional
    fun resolve(scope: TenantScope, namespaceId: String, caseId: String, workflowId: String): DeliveryResolution {
        requireIdentity(namespaceId, caseId, workflowId)
        val environment = environmentRepository.findLatestByWorkflowId(scope, workflowId)
            ?: throw deliveryException(DeliveryErrorCodes.DELIVERY_BINDING_UNAVAILABLE, "No environment for workflow")
        if (environment.lifecycleState == WorkEnvironmentState.DECOMMISSIONED) {
            throw deliveryException(DeliveryErrorCodes.DELIVERY_BINDING_UNAVAILABLE, "Environment is decommissioned")
        }
        if (environment.parentCaseId != caseId || environment.workflowId != workflowId) {
            throw deliveryException(DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH, "Environment binding mismatch")
        }
        val reconciliation = gitProvisioner.reconcile(environment)
        if (reconciliation !is GitReconciliation.Owned) {
            throw deliveryException(DeliveryErrorCodes.DELIVERY_BINDING_UNAVAILABLE, "Worktree not owned")
        }
        val headCommit = reconciliation.headCommit ?: environment.baseCommit
            ?: throw deliveryException(DeliveryErrorCodes.DELIVERY_BINDING_UNAVAILABLE, "No head commit")
        val environmentHash = environmentHash(environment, headCommit)
        val deliveryId = "$workflowId-delivery"
        val runtimeId = "factory-dashboard"

        val existing = repository.read(scope, namespaceId, deliveryId)
        if (existing == null) {
            val created = repository.create(
                scope,
                newSnapshot(namespaceId, deliveryId, workflowId, environment, environmentHash, caseId, runtimeId, headCommit),
            )
            if (!created.ok) {
                throw deliveryException(created.error?.code ?: DeliveryErrorCodes.DELIVERY_CONTROL_PLANE_FAILURE)
            }
            val snapshot = created.snapshot
                ?: throw deliveryException(DeliveryErrorCodes.DELIVERY_CONTROL_PLANE_FAILURE)
            return DeliveryResolution(snapshot, environment, headCommit)
        }
        if (existing["workflowId"] != workflowId ||
            existing["environmentId"] != environment.environmentId ||
            existing["environmentHash"] != environmentHash ||
            existing["parentCaseId"] != caseId ||
            existing["runtimeId"] != runtimeId
        ) {
            throw deliveryException(DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH, "Delivery binding mismatch")
        }
        if (repository.hasIndeterminateOperation(scope, namespaceId, deliveryId)) {
            throw deliveryException(DeliveryErrorCodes.DELIVERY_INDETERMINATE_OPERATION_PENDING)
        }
        if (existing["headCommit"] != headCommit) {
            throw deliveryException(
                DeliveryErrorCodes.DELIVERY_HEAD_RECONCILIATION_REQUIRED,
                "Delivery head does not match the reconciled worktree",
                mapOf("expectedHead" to existing["headCommit"], "observedHead" to headCommit),
            )
        }
        return DeliveryResolution(existing, environment, headCommit)
    }

    /** GET status: the snapshot plus its live operations and rollback requests. */
    fun status(scope: TenantScope, namespaceId: String, caseId: String, workflowId: String): Map<String, Any?> {
        val resolved = resolve(scope, namespaceId, caseId, workflowId)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val projection = repository.inspectDeliveryOperations(scope, namespaceId, deliveryId)
        return resolved.snapshot + mapOf(
            "deliveryOperations" to projection.operations,
            "unresolvedIndeterminate" to projection.unresolvedIndeterminate,
            "rollbackRequests" to projection.rollbackRequests,
        )
    }

    /** POST checkpoint: create a git checkpoint commit and persist the new head. */
    fun checkpoint(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult {
        requireTrustedBody(body, setOf("expectedHead", "message", "claims", "idempotencyKey"))
        val input = body!!
        val resolved = resolve(scope, namespaceId, caseId, workflowId)
        val expectedHead = input["expectedHead"] as? String
        if (expectedHead == null ||
            expectedHead != resolved.headCommit ||
            resolved.snapshot["headCommit"] != expectedHead
        ) {
            throw deliveryException(DeliveryErrorCodes.STALE_HEAD)
        }
        val binding = binding(resolved, expectedHead)
        val gitResult = git.checkpoint(binding, input["message"] as? String ?: "", claimsOf(input["claims"]))
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val idempotencyKey = (input["idempotencyKey"] as? String) ?: "checkpoint:$expectedHead"
        val patch = mapOf(
            "headCommit" to gitResult.commit,
            "updatedAt" to nowIso(),
            "git.checkpoint" to mapOf(
                "commit" to gitResult.commit,
                "previousHead" to (gitResult.previousHead ?: expectedHead),
                "changed" to gitResult.changed,
                "diffHash" to gitResult.inspection.diffHash,
                "timestamp" to nowIso(),
            ),
        )
        requireOk(
            repository.updateSnapshot(
                scope,
                namespaceId,
                deliveryId,
                patch,
                mapOf(
                    "kind" to "git-checkpoint",
                    "idempotencyKey" to idempotencyKey,
                    "facts" to mapOf("commit" to gitResult.commit, "changed" to gitResult.changed),
                ),
            ),
        )
        val data = mapOf(
            "changed" to gitResult.changed,
            "commit" to gitResult.commit,
            "previousHead" to gitResult.previousHead,
            "inspection" to mapOf(
                "worktreePath" to gitResult.inspection.worktreePath,
                "branch" to gitResult.inspection.branch,
                "headCommit" to gitResult.inspection.headCommit,
                "files" to gitResult.inspection.files,
                "diffHash" to gitResult.inspection.diffHash,
            ),
        )
        return DeliveryHttpResult(if (gitResult.changed) 201 else 200, data)
    }

    /** POST push: push the delivery branch and persist the remote push result. */
    fun push(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult {
        requireTrustedBody(body, setOf("expectedHead", "idempotencyKey"))
        val input = body!!
        val resolved = resolve(scope, namespaceId, caseId, workflowId)
        val expectedHead = (input["expectedHead"] as? String) ?: resolved.snapshot["headCommit"] as String
        val result = git.push(binding(resolved, expectedHead))
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val idempotencyKey = (input["idempotencyKey"] as? String) ?: "push:$expectedHead"
        if (result is DeliveryGitPushResult.Blocked) {
            journalFailure(scope, namespaceId, deliveryId, "git-push", result.errorCode, idempotencyKey)
            throw deliveryException(result.errorCode)
        }
        val ok = result as DeliveryGitPushResult.Ok
        val patch = mapOf(
            "updatedAt" to nowIso(),
            "git.push" to mapOf(
                "headCommit" to ok.headCommit,
                "changed" to ok.changed,
                "remote" to git.remote,
                "branch" to resolved.environment.branch,
                "timestamp" to nowIso(),
            ),
        )
        requireOk(
            repository.updateSnapshot(
                scope,
                namespaceId,
                deliveryId,
                patch,
                mapOf(
                    "kind" to "git-push",
                    "idempotencyKey" to idempotencyKey,
                    "facts" to mapOf("headCommit" to ok.headCommit, "changed" to ok.changed),
                ),
            ),
        )
        return DeliveryHttpResult(
            200,
            mapOf(
                "ok" to true,
                "changed" to ok.changed,
                "headCommit" to ok.headCommit,
                "previousRemoteHead" to ok.previousRemoteHead,
            ),
        )
    }

    /** POST pull-request: create a draft pull request and persist its projection. */
    fun pullRequest(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult {
        requireTrustedBody(body, setOf("title", "body", "idempotencyKey"))
        val input = body!!
        val resolved = resolve(scope, namespaceId, caseId, workflowId)
        val configuration = properties.pullRequest
            ?: throw deliveryException(DeliveryErrorCodes.PULL_REQUEST_NOT_CONFIGURED)
        val context = DeliveryPullRequestContext(
            owner = configuration.owner,
            repo = configuration.repo,
            baseBranch = configuration.baseBranch,
            headBranch = resolved.environment.branch,
            title = input["title"] as? String,
            body = input["body"] as? String,
            idempotencyKey = input["idempotencyKey"] as? String,
        )
        val result = pullRequests.createDraft(context)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val idempotencyKey = (input["idempotencyKey"] as? String) ?: "pr:${resolved.environment.branch}"
        when (result) {
            is DeliveryPullRequestResult.Blocked -> {
                journalFailure(scope, namespaceId, deliveryId, "pull-request", result.errorCode, idempotencyKey)
                throw deliveryException(result.errorCode)
            }
            is DeliveryPullRequestResult.Ok -> {
                val pullRequest = result.pullRequest
                val patch = mapOf(
                    "updatedAt" to nowIso(),
                    "git.pullRequest" to mapOf(
                        "id" to pullRequest.id,
                        "url" to pullRequest.url,
                        "draft" to pullRequest.draft,
                        "state" to pullRequest.state,
                        "reused" to result.reused,
                        "timestamp" to nowIso(),
                    ),
                )
                requireOk(
                    repository.updateSnapshot(
                        scope,
                        namespaceId,
                        deliveryId,
                        patch,
                        mapOf(
                            "kind" to "pull-request-persisted",
                            "idempotencyKey" to "pr-persisted:${pullRequest.id}",
                            "facts" to mapOf("id" to pullRequest.id),
                        ),
                    ),
                )
                return DeliveryHttpResult(
                    if (result.reused) 200 else 201,
                    mapOf(
                        "id" to pullRequest.id,
                        "url" to pullRequest.url,
                        "draft" to pullRequest.draft,
                        "state" to pullRequest.state,
                    ),
                )
            }
        }
    }

    /** POST promote: evaluate and apply an ordered, evidence-gated promotion. */
    fun promote(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        body: Map<String, Any?>?,
    ): DeliveryHttpResult {
        requireTrustedBody(body, setOf("deliveryId", "expectedRevision", "requestedStage", "evidenceIds", "idempotencyKey"))
        val resolved = resolve(scope, namespaceId, caseId, workflowId)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val validation = validateDeliveryPromotionRequest(body, deliveryId)
        if (validation is DeliveryPromotionRequestValidation.Invalid) {
            throw deliveryException(validation.code)
        }
        val request = (validation as DeliveryPromotionRequestValidation.Valid).value
        val evidence = evidenceStore.list(scope, namespaceId, deliveryId)
        val execution = DeliveryExecutionContext(
            kind = if (request.requestedStage == "release-approved") "factory-human" else "factory-control-plane",
            namespaceId = namespaceId,
            workflowId = workflowId,
            caseId = caseId,
            runtimeId = "factory-dashboard",
            actorId = actorId,
        )
        val result = repository.promote(
            scope,
            DeliveryStorePromoteInput(
                namespaceId = namespaceId,
                request = request,
                definition = definition,
                evidence = evidence,
                execution = execution,
            ),
        )
        if (!result.ok) {
            throw deliveryException(result.error?.code ?: DeliveryErrorCodes.DELIVERY_CONTROL_PLANE_FAILURE, result.error?.reason ?: "")
        }
        return DeliveryHttpResult(if (result.changed) 201 else 200, result.snapshot)
    }

    /**
     * Record a Factory-only delivery evidence entry.
     *
     * `sourceKind` must be one of the trusted internal producers and agents are
     * forbidden from writing deployment / smoke / rollback evidence.
     */
    fun recordEvidence(
        scope: TenantScope,
        namespaceId: String,
        caseId: String,
        workflowId: String,
        actorId: String,
        body: Map<String, Any?>?,
        sourceKind: String,
    ): DeliveryHttpResult {
        val allowedSourceKinds = setOf("factory-build", "factory-human")
        val forbiddenAuthority = setOf("deployment-result", "smoke-result", "rollback-result")
        if (sourceKind !in allowedSourceKinds || body?.get("kind") in forbiddenAuthority) {
            throw io.whozoss.factory.delivery.domain.DeliveryException(
                "DELIVERY_EVIDENCE_AUTHORITY_FORBIDDEN",
                "Delivery evidence authority forbidden",
                403,
            )
        }
        val resolved = resolve(scope, namespaceId, caseId, workflowId)
        val deliveryId = resolved.snapshot["deliveryId"] as String
        val input = LinkedHashMap(body ?: emptyMap())
        input["deliveryId"] = deliveryId
        input["workflowId"] = workflowId
        input["environmentHash"] = resolved.snapshot["environmentHash"]
        input["caseId"] = caseId
        input["runtimeId"] = "factory-dashboard"
        input["headCommit"] = resolved.snapshot["headCommit"]
        val result = evidenceStore.record(
            scope,
            namespaceId,
            input,
            mapOf("kind" to sourceKind, "actorId" to actorId),
        )
        if (!result.ok) {
            throw deliveryException(result.errorCode ?: DeliveryErrorCodes.INVALID_DELIVERY_EVIDENCE)
        }
        return DeliveryHttpResult(if (result.created) 201 else 200, result.evidence)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun buildDefinition(): HashedDeliveryDefinition {
        val validated = validateDeliveryDefinition(defaultDeliveryDefinition().toMap())
        val definition = when (validated) {
            is DeliveryDefinitionValidation.Valid -> validated.definition
            is DeliveryDefinitionValidation.Invalid ->
                throw IllegalStateException("Invalid default delivery definition: ${validated.path}")
        }
        return HashedDeliveryDefinition(definition, hashDeliveryDefinition(definition))
    }

    private fun requireIdentity(namespaceId: String, caseId: String, workflowId: String) {
        if (!DELIVERY_UUID.matches(namespaceId) || !DELIVERY_UUID.matches(caseId) || !DELIVERY_SAFE.matches(workflowId)) {
            throw deliveryException(DeliveryErrorCodes.INVALID_TRUST_CONTEXT)
        }
    }

    private fun requireTrustedBody(body: Map<String, Any?>?, allowed: Set<String>) {
        if (body == null ||
            body.keys.any { it in FORBIDDEN_BODY_FIELDS } ||
            body.keys.any { it !in allowed }
        ) {
            throw deliveryException(DeliveryErrorCodes.UNTRUSTED_DELIVERY_INPUT)
        }
    }

    private fun binding(resolved: DeliveryResolution, expectedHead: String): DeliveryGitWorktreeBinding =
        DeliveryGitWorktreeBinding(
            worktreePath = resolved.environment.worktreePath,
            branch = resolved.environment.branch,
            baseCommit = resolved.environment.baseCommit ?: expectedHead,
            expectedHead = expectedHead,
        )

    private fun journalFailure(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        kind: String,
        code: String,
        idempotencyKey: String,
    ) {
        repository.updateSnapshot(
            scope,
            namespaceId,
            deliveryId,
            mapOf("updatedAt" to nowIso()),
            mapOf("kind" to kind, "idempotencyKey" to idempotencyKey, "facts" to mapOf("code" to code)),
        )
    }

    private fun requireOk(result: DeliveryWriteResult) {
        if (!result.ok) {
            throw deliveryException(result.error?.code ?: DeliveryErrorCodes.DELIVERY_CONTROL_PLANE_FAILURE)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun claimsOf(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    private fun environmentHash(environment: WorkEnvironment, headCommit: String): String =
        CanonicalHash.canonicalDeliveryHash(
            mapOf(
                "environmentId" to environment.environmentId,
                "workflowId" to environment.workflowId,
                "namespaceId" to environment.namespaceId,
                "branch" to environment.branch,
                "baseCommit" to environment.baseCommit,
                "headCommit" to headCommit,
            ),
        )

    private fun newSnapshot(
        namespaceId: String,
        deliveryId: String,
        workflowId: String,
        environment: WorkEnvironment,
        environmentHash: String,
        caseId: String,
        runtimeId: String,
        headCommit: String,
    ): Map<String, Any?> = mapOf(
        "schemaVersion" to "1",
        "deliveryId" to deliveryId,
        "namespaceId" to namespaceId,
        "workflowId" to workflowId,
        "environmentId" to environment.environmentId,
        "environmentHash" to environmentHash,
        "parentCaseId" to caseId,
        "runtimeId" to runtimeId,
        "worktreePath" to environment.worktreePath,
        "branch" to environment.branch,
        "baseCommit" to (environment.baseCommit ?: headCommit),
        "headCommit" to headCommit,
        "definitionType" to definition.definition.deliveryType,
        "definitionVersion" to definition.definition.version,
        "definitionHash" to definition.definitionHash,
        "stage" to "implementation-ready",
        "revision" to 1,
        "evidenceIds" to emptyList<String>(),
        "createdAt" to environment.createdAt.toString(),
        "updatedAt" to nowIso(),
        "git" to mapOf("checkpoint" to null, "push" to null, "pullRequest" to null),
        "artifact" to mapOf("state" to "pending"),
        "release" to mapOf("state" to "pending"),
        "deployment" to mapOf("state" to "pending"),
        "verification" to mapOf("state" to "pending"),
        "blockers" to emptyList<String>(),
    )
}
