package io.whozoss.factory.delivery.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.whozoss.factory.delivery.domain.CanonicalHash
import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import io.whozoss.factory.delivery.domain.DeliveryEvidenceItem
import io.whozoss.factory.delivery.domain.DeliveryExecutionContext
import io.whozoss.factory.delivery.domain.DeliveryOperationObservation
import io.whozoss.factory.delivery.domain.DeliveryPromotionEvaluation
import io.whozoss.factory.delivery.domain.DeliveryPromotionSnapshot
import io.whozoss.factory.delivery.domain.applyDeliveryPromotion
import io.whozoss.factory.delivery.domain.deriveDeliveryOperationIdentity
import io.whozoss.factory.delivery.domain.deliveryScopeHash
import io.whozoss.factory.delivery.domain.deliverySemanticHash
import io.whozoss.factory.delivery.domain.evaluateDeliveryPromotion
import io.whozoss.factory.delivery.domain.DeliveryPromotionDecision
import io.whozoss.factory.delivery.domain.DeliveryOperationIdentityDerivation
import io.whozoss.factory.delivery.domain.DeliveryOperationRequestNormalization
import io.whozoss.factory.delivery.domain.DeliveryOperationScope
import io.whozoss.factory.delivery.domain.DeliveryOperationTransitionValidation
import io.whozoss.factory.delivery.domain.nowIso
import io.whozoss.factory.delivery.domain.normalizeDeliveryOperationRequest
import io.whozoss.factory.delivery.domain.validateDeliveryOperationRecord
import io.whozoss.factory.delivery.domain.validateDeliveryOperationTransition
import io.whozoss.factory.persistence.TenantScope
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Repository
import java.security.MessageDigest
import java.time.Instant

/**
 * Neo4j implementation of [DeliveryRepository].
 *
 * Replaces `SqlDeliveryRepository`. The durable surface is a `:Delivery` node
 * (optimistic-locking `revision` plus the verbatim snapshot JSON) and the
 * append-only `:DeliveryRecord` journal: every promotion, delivery-operation
 * transition and rollback-request decision is one immutable node, projected
 * back into the live operations exactly like the Node adapter. The journal
 * sequence is generated as `MAX(recordSequence) + 1` in the same transaction.
 *
 * Every multi-write mutation runs inside one Spring transaction, and the
 * domain-level idempotency/optimistic-locking rules are unchanged from the
 * relational adapter.
 */
@Repository
@Primary
class Neo4jDeliveryRepository(
    private val deliveries: SpringDataNeo4jDeliveryRepository,
    private val records: SpringDataNeo4jDeliveryRecordRepository,
    private val objectMapper: ObjectMapper,
) : DeliveryRepository {

    override fun read(scope: TenantScope, namespaceId: String, deliveryId: String): Map<String, Any?>? {
        DeliverySnapshots.assertScope(namespaceId, deliveryId)
        return readInternal(scope, namespaceId, deliveryId)
    }

    override fun create(scope: TenantScope, input: Map<String, Any?>): DeliveryWriteResult {
        val namespaceId = input["namespaceId"] as? String
        val deliveryId = input["deliveryId"] as? String
        DeliverySnapshots.assertScope(namespaceId, deliveryId)
        val current = readInternal(scope, namespaceId!!, deliveryId!!)
        if (current != null) {
            return if (DeliverySnapshots.payloadHash(current) == DeliverySnapshots.payloadHash(input)) {
                DeliveryWriteResult.idempotent(snapshot = current)
            } else {
                DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_IDENTITY_CONFLICT)
            }
        }
        if (!DeliverySnapshots.valid(input)) {
            return DeliveryWriteResult.failure(DeliveryErrorCodes.INVALID_DELIVERY_SNAPSHOT)
        }
        return writeInternal(
            scope,
            null,
            input,
            mapOf("kind" to "delivery_created", "idempotencyKey" to "create:$deliveryId"),
        )
    }

    override fun promote(scope: TenantScope, input: DeliveryStorePromoteInput): DeliveryWriteResult {
        val namespaceId = input.namespaceId
        val deliveryId = input.request.deliveryId
        val current = readInternal(scope, namespaceId, deliveryId)
        val journal = journalInternal(scope, namespaceId, deliveryId)
        val scopeHash = deliveryScopeHash(namespaceId, input.request, input.execution)
        val semanticHash = deliverySemanticHash(input.request)
        val prior = journal.find { it["scopeHash"] == scopeHash && it["state"] == "succeeded" }
        if (prior != null) {
            return if (prior["semanticHash"] != semanticHash) {
                DeliveryWriteResult.failure(DeliveryErrorCodes.IDEMPOTENCY_KEY_COLLISION)
            } else {
                DeliveryWriteResult.idempotent(snapshot = current)
            }
        }
        val decision = evaluateDeliveryPromotion(
            DeliveryPromotionEvaluation(
                request = input.request,
                snapshot = current?.toPromotionSnapshot(),
                definition = input.definition,
                evidence = input.evidence,
                execution = input.execution,
            ),
        )
        if (decision is DeliveryPromotionDecision.Denied) {
            return DeliveryWriteResult.failure(decision.code, decision.reason)
        }
        val next = applyDeliveryPromotion(current!!, input.request)
        return writeInternal(
            scope,
            current,
            next,
            mapOf(
                "kind" to "delivery_promoted",
                "idempotencyKey" to input.request.idempotencyKey,
                "scopeHash" to scopeHash,
                "semanticHash" to semanticHash,
                "evidenceIds" to input.request.evidenceIds,
            ),
        )
    }

    override fun readWithOperations(scope: TenantScope, namespaceId: String, deliveryId: String): Map<String, Any?>? {
        val snapshot = readInternal(scope, namespaceId, deliveryId) ?: return null
        val projection = projection(journalInternal(scope, namespaceId, deliveryId))
        return snapshot + mapOf(
            "deliveryOperations" to projection.operations,
            "rollbackRequests" to projection.rollbackRequests,
        )
    }

    override fun inspectDeliveryOperations(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
    ): DeliveryOperationProjection {
        DeliverySnapshots.assertScope(namespaceId, deliveryId)
        return projection(journalInternal(scope, namespaceId, deliveryId))
    }

    override fun createRollbackRequest(
        scope: TenantScope,
        input: DeliveryStoreRollbackRequestInput,
    ): DeliveryWriteResult {
        val snapshot = readInternal(scope, input.namespaceId, input.deliveryId)
            ?: return DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_NOT_FOUND)
        if (snapshot["workflowId"] != input.workflowId ||
            snapshot["parentCaseId"] != input.caseId ||
            snapshot["runtimeId"] != input.runtimeId
        ) {
            return DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_SCOPE_MISMATCH)
        }
        val projection = projection(journalInternal(scope, input.namespaceId, input.deliveryId))
        val request = input.request
        val prior = projection.rollbackRequestHistory.find { it["scopeHash"] == request["scopeHash"] }
        if (prior != null) {
            return if (prior["semanticHash"] == request["semanticHash"]) {
                DeliveryWriteResult.idempotent(
                    request = projection.rollbackRequests.find { it["rollbackRequestId"] == prior["rollbackRequestId"] }
                        ?: prior,
                )
            } else {
                DeliveryWriteResult.failure(DeliveryErrorCodes.IDEMPOTENCY_KEY_COLLISION)
            }
        }
        if (snapshot["revision"] != request["expectedRevision"]) {
            return DeliveryWriteResult.failure(DeliveryErrorCodes.REVISION_CONFLICT)
        }
        val record = linkedMapOf<String, Any?>(
            "recordType" to "rollback-request",
            "schemaVersion" to "1",
            "rollbackRequestId" to request["rollbackRequestId"],
            "deliveryId" to input.deliveryId,
            "workflowId" to input.workflowId,
            "namespaceId" to input.namespaceId,
            "caseId" to input.caseId,
            "runtimeId" to input.runtimeId,
            "status" to "requested",
            "expectedRevision" to request["expectedRevision"],
            "idempotencyKey" to request["idempotencyKey"],
            "scopeHash" to request["scopeHash"],
            "semanticHash" to request["semanticHash"],
            "targetId" to request["targetId"],
            "targetHash" to request["targetHash"],
            "deploymentRef" to CanonicalHash.canonical(request["deploymentRef"]),
            "priorArtifactRef" to CanonicalHash.canonical(request["priorArtifactRef"]),
            "priorReleaseRef" to CanonicalHash.canonical(request["priorReleaseRef"]),
            "reasonCode" to request["reasonCode"],
            "requestedAt" to nowIso(),
            "requestedBy" to CanonicalHash.canonical(input.execution),
        )
        (request["reason"] as? String)?.let { record["reason"] = it }
        appendInternal(scope, input.namespaceId, input.deliveryId, listOf(record))
        return DeliveryWriteResult.changed(request = record)
    }

    override fun approveRollbackRequest(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        rollbackRequestId: String,
        approval: DeliveryStoreRollbackApprovalInput,
    ): DeliveryWriteResult {
        val snapshot = readInternal(scope, namespaceId, deliveryId)
            ?: return DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_NOT_FOUND)
        val projection = projection(journalInternal(scope, namespaceId, deliveryId))
        val current = projection.rollbackRequests.find { it["rollbackRequestId"] == rollbackRequestId }
            ?: return DeliveryWriteResult.failure(DeliveryErrorCodes.ROLLBACK_REQUEST_NOT_FOUND)
        val scopeHash = CanonicalHash.canonicalDeliveryHash(
            mapOf("rollbackRequestId" to rollbackRequestId, "idempotencyKey" to approval.idempotencyKey),
        )
        val semanticHash = CanonicalHash.canonicalDeliveryHash(
            mapOf(
                "rollbackRequestId" to rollbackRequestId,
                "expectedRevision" to approval.expectedRevision,
                "actorId" to approval.execution["actorId"],
            ),
        )
        val prior = projection.rollbackRequestHistory.find { it["approvalScopeHash"] == scopeHash }
        if (prior != null) {
            return if (prior["approvalSemanticHash"] == semanticHash) {
                DeliveryWriteResult.idempotent(request = prior)
            } else {
                DeliveryWriteResult.failure(DeliveryErrorCodes.IDEMPOTENCY_KEY_COLLISION)
            }
        }
        if (snapshot["revision"] != approval.expectedRevision ||
            (current["expectedRevision"] as? Number)?.toInt() != approval.expectedRevision
        ) {
            return DeliveryWriteResult.failure(DeliveryErrorCodes.REVISION_CONFLICT)
        }
        if (current["status"] != "requested") {
            return DeliveryWriteResult.failure(DeliveryErrorCodes.ROLLBACK_REQUEST_ALREADY_DECIDED)
        }
        val record = LinkedHashMap(current)
        record["status"] = "approved"
        record["approvedAt"] = nowIso()
        record["approvedBy"] = CanonicalHash.canonical(approval.execution)
        record["approvalScopeHash"] = scopeHash
        record["approvalSemanticHash"] = semanticHash
        record["approvalIdempotencyKey"] = approval.idempotencyKey
        appendInternal(scope, namespaceId, deliveryId, listOf(record))
        return DeliveryWriteResult.changed(request = record)
    }

    override fun createDeliveryOperation(
        scope: TenantScope,
        input: DeliveryStoreOperationInput,
    ): DeliveryWriteResult {
        val normalized = normalizeDeliveryOperationRequest(input.request)
        if (normalized is DeliveryOperationRequestNormalization.Invalid) {
            return DeliveryWriteResult.failure(normalized.code, normalized.reason)
        }
        val request = (normalized as DeliveryOperationRequestNormalization.Valid).value
        val snapshot = readInternal(scope, input.namespaceId, input.deliveryId)
            ?: return DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_NOT_FOUND)
        val targetHash = input.targetRef?.get("targetHash") as? String
        val identity = deriveDeliveryOperationIdentity(
            DeliveryOperationScope(
                namespaceId = input.namespaceId,
                workflowId = input.workflowId,
                deliveryId = input.deliveryId,
                caseId = input.caseId,
                runtimeId = input.runtimeId,
            ),
            request,
            targetHash,
        )
        if (identity is DeliveryOperationIdentityDerivation.Invalid) {
            return DeliveryWriteResult.failure(identity.code, identity.reason)
        }
        val derived = (identity as DeliveryOperationIdentityDerivation.Valid).value
        val projection = projection(journalInternal(scope, input.namespaceId, input.deliveryId))
        val existing = projection.history.find { it["scopeHash"] == derived.scopeHash }
        if (existing != null) {
            return if (existing["semanticHash"] == derived.semanticHash) {
                DeliveryWriteResult.idempotent(
                    operation = projection.operations.find { it["operationId"] == existing["operationId"] } ?: existing,
                )
            } else {
                DeliveryWriteResult.failure(DeliveryErrorCodes.IDEMPOTENCY_KEY_COLLISION)
            }
        }
        if ((snapshot["revision"] as? Number)?.toInt() != request.expectedRevision) {
            return DeliveryWriteResult.failure(DeliveryErrorCodes.REVISION_CONFLICT)
        }
        if (projection.unresolvedIndeterminate.isNotEmpty()) {
            return DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_OPERATION_INDETERMINATE)
        }
        val now = nowIso()
        val source = request.artifactRef ?: request.priorArtifactRef
        val operation = linkedMapOf<String, Any?>(
            "recordType" to "delivery-operation",
            "operationId" to derived.operationId,
            "kind" to request.kind,
            "expectedRevision" to request.expectedRevision,
            "targetRef" to CanonicalHash.canonical(input.targetRef),
            "artifactRef" to (request.artifactRef ?: request.priorArtifactRef),
            "releaseRef" to (request.releaseRef ?: request.priorReleaseRef),
            "deploymentRef" to request.deploymentRef,
            "rollbackRef" to request.rollbackRef,
            "state" to "pending",
            "attempt" to 0,
            "requestedAt" to now,
            "execution" to CanonicalHash.canonical(input.execution),
            "scopeHash" to derived.scopeHash,
            "semanticHash" to derived.semanticHash,
            "sourceCommit" to source?.get("sourceCommit"),
            "artifactDigest" to source?.get("digest"),
            "rollbackRequestId" to request.rollbackRequestId,
            "approvedEvidenceId" to request.approvedEvidenceId,
        )
        val persisted = operation.filterValues { it != null }
        val contract = validateDeliveryOperationRecord(persisted)
        if (contract is io.whozoss.factory.delivery.domain.DeliveryOperationRecordValidation.Invalid) {
            return DeliveryWriteResult.failure(contract.code)
        }
        appendInternal(scope, input.namespaceId, input.deliveryId, listOf(persisted))
        return DeliveryWriteResult.changed(operation = persisted)
    }

    override fun recordDeliveryOperation(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        operationId: String,
        transition: DeliveryOperationTransitionInput,
        inspectedObservation: DeliveryOperationObservation?,
    ): DeliveryWriteResult {
        readInternal(scope, namespaceId, deliveryId)
            ?: return DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_NOT_FOUND)
        val projection = projection(journalInternal(scope, namespaceId, deliveryId))
        val previous = projection.operations.find { it["operationId"] == operationId }
            ?: return DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_OPERATION_NOT_FOUND)
        val now = nowIso()
        val state = transition.state
        val next = LinkedHashMap(previous)
        next["state"] = state
        next["attempt"] = if (state == "running") {
            ((previous["attempt"] as? Number)?.toInt() ?: 0) + 1
        } else {
            previous["attempt"]
        }
        if (state == "running") next["startedAt"] = now else previous["startedAt"]?.let { next["startedAt"] = it }
        if (state in listOf("succeeded", "failed")) next["completedAt"] = now else next.remove("completedAt")
        next["adapterCorrelation"] = transition.adapterCorrelation ?: previous["adapterCorrelation"]
        if (transition.result != null) next["result"] = transition.result
        if (transition.error != null) next["error"] = transition.error
        if (transition.resolvedOperationId != null) next["resolvedOperationId"] = transition.resolvedOperationId
        val clean = next.filterValues { it != null }
        val valid = validateDeliveryOperationTransition(previous, clean, inspectedObservation)
        if (valid is DeliveryOperationTransitionValidation.Invalid) {
            return DeliveryWriteResult.failure(valid.code)
        }
        val contract = validateDeliveryOperationRecord(clean)
        if (contract is io.whozoss.factory.delivery.domain.DeliveryOperationRecordValidation.Invalid) {
            return DeliveryWriteResult.failure(contract.code, contract.path)
        }
        appendInternal(scope, namespaceId, deliveryId, listOf(clean))
        return DeliveryWriteResult.changed(operation = clean)
    }

    override fun startDeliveryOperation(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        operationId: String,
        adapterCorrelation: Any?,
    ): DeliveryWriteResult = recordDeliveryOperation(
        scope,
        namespaceId,
        deliveryId,
        operationId,
        DeliveryOperationTransitionInput(state = "running", adapterCorrelation = adapterCorrelation),
        null,
    )

    override fun reconcileDeliveryOperation(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        operationId: String,
        observation: DeliveryOperationObservation,
    ): DeliveryWriteResult = recordDeliveryOperation(
        scope,
        namespaceId,
        deliveryId,
        operationId,
        DeliveryOperationTransitionInput(
            state = observation.state ?: "",
            result = observation.result,
            error = observation.error,
            adapterCorrelation = observation.adapterCorrelation,
            resolvedOperationId = operationId,
        ),
        observation.copy(operationId = operationId),
    )

    override fun hasIndeterminateOperation(scope: TenantScope, namespaceId: String, deliveryId: String): Boolean = try {
        inspectDeliveryOperations(scope, namespaceId, deliveryId).unresolvedIndeterminate.isNotEmpty()
    } catch (_: Exception) {
        true
    }

    override fun updateSnapshot(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        patch: Map<String, Any?>,
        operationInput: Map<String, Any?>,
    ): DeliveryWriteResult {
        val current = readInternal(scope, namespaceId, deliveryId)
            ?: return DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_NOT_FOUND)
        val updated = LinkedHashMap(current)
        for ((key, value) in patch) {
            val parts = key.split(".")
            when {
                parts.size == 1 -> updated[key] = value
                parts.size == 2 -> {
                    val head = parts[0]
                    val tail = parts[1]
                    val nested = LinkedHashMap(
                        (updated[head] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap(),
                    )
                    nested[tail] = value
                    updated[head] = nested
                }
                else -> updated[key] = value
            }
        }
        updated["updatedAt"] = patch["updatedAt"] ?: nowIso()
        return writeInternal(scope, current, updated, operationInput)
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun readInternal(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
    ): Map<String, Any?>? {
        val node = deliveries
            .findById(
                DeliveryNode.compositeId(scope.organizationId, scope.workstreamId, namespaceId, deliveryId),
            ).orElse(null)
            ?.takeIf { it.organizationId == scope.organizationId && it.workstreamId == scope.workstreamId }
            ?: return null
        val snapshot = deserialize(node.payload)
        if (!DeliverySnapshots.valid(snapshot) || snapshot["snapshotHash"] != DeliverySnapshots.payloadHash(snapshot)) {
            throw io.whozoss.factory.delivery.domain.deliveryException("CORRUPT_DELIVERY_STORAGE")
        }
        return snapshot
    }

    private fun journalInternal(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
    ): List<Map<String, Any?>> =
        records
            .findByScopeAndDelivery(scope.organizationId, scope.workstreamId, namespaceId, deliveryId)
            .map { deserialize(it.payload) }

    private fun appendInternal(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        journalRecords: List<Map<String, Any?>>,
    ) {
        var sequence = maxSequence(scope, namespaceId, deliveryId) + 1
        for (record in journalRecords) {
            records.save(
                DeliveryRecordNode(
                    id = DeliveryRecordNode.compositeId(
                        scope.organizationId,
                        scope.workstreamId,
                        namespaceId,
                        deliveryId,
                        sequence,
                    ),
                    organizationId = scope.organizationId,
                    workstreamId = scope.workstreamId,
                    namespaceId = namespaceId,
                    deliveryId = deliveryId,
                    recordSequence = sequence,
                    recordId = "$deliveryId:$sequence",
                    recordType = record["recordType"] as? String,
                    payload = serialize(record),
                    createdAt = Instant.now(),
                ),
            )
            sequence++
        }
    }

    private fun maxSequence(scope: TenantScope, namespaceId: String, deliveryId: String): Long =
        records.maxRecordSequence(scope.organizationId, scope.workstreamId, namespaceId, deliveryId)

    private fun projection(recordsList: List<Map<String, Any?>>): DeliveryOperationProjection {
        val history = recordsList.filter { it["recordType"] == "delivery-operation" }
        val current = LinkedHashMap<String, Map<String, Any?>>()
        val resolved = history
            .filter { it["resolvedOperationId"] != null && it["state"] in listOf("succeeded", "failed") }
            .mapNotNull { it["resolvedOperationId"]?.toString() }
            .toSet()
        for (record in history) current[record["operationId"]?.toString() ?: ""] = record
        val rollbackHistory = recordsList.filter { it["recordType"] == "rollback-request" }
        val rollbackCurrent = LinkedHashMap<String, Map<String, Any?>>()
        for (record in rollbackHistory) rollbackCurrent[record["rollbackRequestId"]?.toString() ?: ""] = record
        val operations = current.values.toList()
        return DeliveryOperationProjection(
            history = history,
            operations = operations,
            rollbackRequests = rollbackCurrent.values.toList(),
            rollbackRequestHistory = rollbackHistory,
            unresolvedIndeterminate = operations.filter {
                it["state"] == "indeterminate" && it["operationId"]?.toString() !in resolved
            },
        )
    }

    private fun writeInternal(
        scope: TenantScope,
        current: Map<String, Any?>?,
        value: Map<String, Any?>,
        operationInput: Map<String, Any?>,
    ): DeliveryWriteResult {
        val namespaceId = value["namespaceId"] as String
        val deliveryId = value["deliveryId"] as String
        val idempotencyKey = operationInput["idempotencyKey"] as String
        val operationId = rawSha256("$namespaceId:$deliveryId:$idempotencyKey")
        val revision = ((current?.get("revision") as? Number)?.toInt() ?: 0) + if (current != null) 1 else 0
        val operation = linkedMapOf<String, Any?>(
            "schemaVersion" to "1",
            "operationId" to operationId,
            "deliveryId" to deliveryId,
            "revision" to revision,
            "kind" to operationInput["kind"],
            "state" to "pending",
            "timestamp" to nowIso(),
        )
        (operationInput["scopeHash"] as? String)?.let {
            operation["scopeHash"] = it
            operation["semanticHash"] = operationInput["semanticHash"]
        }
        (operationInput["evidenceIds"] as? List<*>)?.let { ids ->
            operation["evidenceIds"] = ids.map { it.toString() }.sorted()
        }
        val clean = value.filterKeys { it != "snapshotHash" }
        val snapshot = LinkedHashMap(clean)
        snapshot["snapshotHash"] = DeliverySnapshots.payloadHash(clean)
        val running = LinkedHashMap(operation).apply { this["state"] = "running"; this["timestamp"] = nowIso() }
        val succeeded = LinkedHashMap(operation).apply {
            this["state"] = "succeeded"
            this["timestamp"] = nowIso()
            this["resultHash"] = snapshot["snapshotHash"]
        }
        val updatedAt = (snapshot["updatedAt"] as? String)?.let { parseInstant(it) } ?: Instant.now()
        val createdAt = (snapshot["createdAt"] as? String)?.let { parseInstant(it) } ?: Instant.now()
        deliveries.save(
            DeliveryNode(
                id = DeliveryNode.compositeId(scope.organizationId, scope.workstreamId, namespaceId, deliveryId),
                organizationId = scope.organizationId,
                workstreamId = scope.workstreamId,
                namespaceId = namespaceId,
                deliveryId = deliveryId,
                revision = (snapshot["revision"] as? Number)?.toInt() ?: 1,
                stage = snapshot["stage"] as? String,
                payload = serialize(snapshot),
                createdAt = createdAt,
                updatedAt = updatedAt,
            ),
        )
        appendInternal(scope, namespaceId, deliveryId, listOf(operation, running, succeeded))
        return DeliveryWriteResult.changed(snapshot = snapshot)
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserialize(json: String?): Map<String, Any?> =
        if (json.isNullOrBlank()) emptyMap() else objectMapper.readValue(json)

    private fun serialize(value: Any?): String = objectMapper.writeValueAsString(value)

    private fun parseInstant(value: String): Instant = try {
        Instant.parse(value)
    } catch (_: Exception) {
        Instant.now()
    }

    private fun rawSha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.toPromotionSnapshot(): DeliveryPromotionSnapshot = DeliveryPromotionSnapshot(
    revision = (this["revision"] as? Number)?.toInt() ?: 0,
    namespaceId = this["namespaceId"] as String,
    workflowId = this["workflowId"] as String,
    parentCaseId = this["parentCaseId"] as String,
    definitionHash = this["definitionHash"] as String,
    stage = this["stage"] as String,
    deliveryId = this["deliveryId"] as String,
    environmentHash = this["environmentHash"] as String,
    headCommit = this["headCommit"] as String,
    evidenceIds = (this["evidenceIds"] as? List<*>)?.map { it.toString() } ?: emptyList(),
)
