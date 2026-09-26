package io.whozoss.factory.delivery.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.whozoss.factory.delivery.domain.CanonicalHash
import io.whozoss.factory.delivery.domain.DeliveryErrorCodes
import io.whozoss.factory.delivery.domain.DeliveryEvidenceItem
import io.whozoss.factory.delivery.domain.DeliveryExecutionContext
import io.whozoss.factory.delivery.domain.DeliveryOperationObservation
import io.whozoss.factory.delivery.domain.DeliveryOperationPolicyExistingOperation
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
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant

/**
 * `NamedParameterJdbcTemplate` implementation of [DeliveryRepository].
 *
 * Port of `factory/src/adapters/persistence/sql/sql-delivery-repository.ts`.
 * The durable surface is a `deliveries` snapshot row (optimistic-locking
 * `revision` plus the verbatim JSONB payload) and the append-only
 * `delivery_journal`: every promotion, delivery-operation transition and
 * rollback-request decision is one immutable row, projected back into the live
 * operations exactly like the Node adapter. Every multi-write mutation runs
 * inside one Spring transaction.
 */
@Repository
class SqlDeliveryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : DeliveryRepository {

    @Transactional(readOnly = true)
    override fun read(scope: TenantScope, namespaceId: String, deliveryId: String): Map<String, Any?>? {
        DeliverySnapshots.assertScope(namespaceId, deliveryId)
        return readInternal(scope, namespaceId, deliveryId, forUpdate = false)
    }

    @Transactional
    override fun create(scope: TenantScope, input: Map<String, Any?>): DeliveryWriteResult {
        val namespaceId = input["namespaceId"] as? String
        val deliveryId = input["deliveryId"] as? String
        DeliverySnapshots.assertScope(namespaceId, deliveryId)
        val current = readInternal(scope, namespaceId!!, deliveryId!!, forUpdate = true)
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

    @Transactional
    override fun promote(scope: TenantScope, input: DeliveryStorePromoteInput): DeliveryWriteResult {
        val namespaceId = input.namespaceId
        val deliveryId = input.request.deliveryId
        val current = readInternal(scope, namespaceId, deliveryId, forUpdate = true)
        val records = journalInternal(scope, namespaceId, deliveryId)
        val scopeHash = deliveryScopeHash(namespaceId, input.request, input.execution)
        val semanticHash = deliverySemanticHash(input.request)
        val prior = records.find { it["scopeHash"] == scopeHash && it["state"] == "succeeded" }
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

    @Transactional(readOnly = true)
    override fun readWithOperations(scope: TenantScope, namespaceId: String, deliveryId: String): Map<String, Any?>? {
        val snapshot = readInternal(scope, namespaceId, deliveryId, forUpdate = false) ?: return null
        val projection = projection(journalInternal(scope, namespaceId, deliveryId))
        return snapshot + mapOf(
            "deliveryOperations" to projection.operations,
            "rollbackRequests" to projection.rollbackRequests,
        )
    }

    @Transactional(readOnly = true)
    override fun inspectDeliveryOperations(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
    ): DeliveryOperationProjection {
        DeliverySnapshots.assertScope(namespaceId, deliveryId)
        return projection(journalInternal(scope, namespaceId, deliveryId))
    }

    @Transactional
    override fun createRollbackRequest(
        scope: TenantScope,
        input: DeliveryStoreRollbackRequestInput,
    ): DeliveryWriteResult {
        val snapshot = readInternal(scope, input.namespaceId, input.deliveryId, forUpdate = true)
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

    @Transactional
    override fun approveRollbackRequest(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        rollbackRequestId: String,
        approval: DeliveryStoreRollbackApprovalInput,
    ): DeliveryWriteResult {
        val snapshot = readInternal(scope, namespaceId, deliveryId, forUpdate = true)
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

    @Transactional
    override fun createDeliveryOperation(
        scope: TenantScope,
        input: DeliveryStoreOperationInput,
    ): DeliveryWriteResult {
        val normalized = normalizeDeliveryOperationRequest(input.request)
        if (normalized is DeliveryOperationRequestNormalization.Invalid) {
            return DeliveryWriteResult.failure(normalized.code, normalized.reason)
        }
        val request = (normalized as DeliveryOperationRequestNormalization.Valid).value
        val snapshot = readInternal(scope, input.namespaceId, input.deliveryId, forUpdate = true)
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

    @Transactional
    override fun recordDeliveryOperation(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        operationId: String,
        transition: DeliveryOperationTransitionInput,
        inspectedObservation: DeliveryOperationObservation?,
    ): DeliveryWriteResult {
        readInternal(scope, namespaceId, deliveryId, forUpdate = true)
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

    @Transactional
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

    @Transactional
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

    @Transactional(readOnly = true)
    override fun hasIndeterminateOperation(scope: TenantScope, namespaceId: String, deliveryId: String): Boolean = try {
        inspectDeliveryOperations(scope, namespaceId, deliveryId).unresolvedIndeterminate.isNotEmpty()
    } catch (_: Exception) {
        true
    }

    @Transactional
    override fun updateSnapshot(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        patch: Map<String, Any?>,
        operationInput: Map<String, Any?>,
    ): DeliveryWriteResult {
        val current = readInternal(scope, namespaceId, deliveryId, forUpdate = true)
            ?: return DeliveryWriteResult.failure(DeliveryErrorCodes.DELIVERY_NOT_FOUND)
        val updated = LinkedHashMap(current)
        for ((key, value) in patch) {
            val parts = key.split(".")
            when {
                parts.size == 1 -> updated[key] = value
                parts.size == 2 -> {
                    val head = parts[0]
                    val tail = parts[1]
                    val nested = LinkedHashMap((updated[head] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap())
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
        forUpdate: Boolean,
    ): Map<String, Any?>? {
        val sql = buildString {
            append("SELECT revision, payload FROM deliveries")
            append(" WHERE organization_id = :organizationId AND workstream_id = :workstreamId")
            append(" AND namespace_id = :namespaceId AND delivery_id = :deliveryId")
            if (forUpdate) append(" FOR UPDATE")
        }
        val json = jdbc.query(
            sql,
            deliveryParams(scope, namespaceId, deliveryId),
        ) { rs, _ -> rs.getString("payload") }.firstOrNull() ?: return null
        val snapshot = deserialize(json)
        if (!DeliverySnapshots.valid(snapshot) || snapshot["snapshotHash"] != DeliverySnapshots.payloadHash(snapshot)) {
            throw io.whozoss.factory.delivery.domain.deliveryException("CORRUPT_DELIVERY_STORAGE")
        }
        return snapshot
    }

    private fun journalInternal(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
    ): List<Map<String, Any?>> = jdbc.query(
        """
        SELECT payload FROM delivery_journal
         WHERE organization_id = :organizationId
           AND workstream_id = :workstreamId
           AND namespace_id = :namespaceId
           AND delivery_id = :deliveryId
         ORDER BY record_sequence ASC
        """.trimIndent(),
        deliveryParams(scope, namespaceId, deliveryId),
    ) { rs, _ -> rs.getString("payload") }.map { deserialize(it) }

    private fun appendInternal(
        scope: TenantScope,
        namespaceId: String,
        deliveryId: String,
        records: List<Map<String, Any?>>,
    ) {
        var sequence = maxSequence(scope, namespaceId, deliveryId) + 1
        for (record in records) {
            val params = deliveryParams(scope, namespaceId, deliveryId)
                .addValue("recordSequence", sequence)
                .addValue("recordId", "$deliveryId:$sequence")
                .addValue("recordType", record["recordType"] as? String)
                .addValue("payload", serialize(record))
                .addValue("createdAt", Timestamp.from(Instant.now()))
            jdbc.update(
                """
                INSERT INTO delivery_journal (
                    organization_id, workstream_id, namespace_id, delivery_id, record_sequence,
                    record_id, record_type, payload, created_at
                ) VALUES (
                    :organizationId, :workstreamId, :namespaceId, :deliveryId, :recordSequence,
                    :recordId, :recordType, CAST(:payload AS jsonb), :createdAt
                )
                """.trimIndent(),
                params,
            )
            sequence++
        }
    }

    private fun maxSequence(scope: TenantScope, namespaceId: String, deliveryId: String): Int =
        jdbc.queryForObject(
            """
            SELECT COALESCE(MAX(record_sequence), 0) FROM delivery_journal
             WHERE organization_id = :organizationId
               AND workstream_id = :workstreamId
               AND namespace_id = :namespaceId
               AND delivery_id = :deliveryId
            """.trimIndent(),
            deliveryParams(scope, namespaceId, deliveryId),
            Int::class.java,
        ) ?: 0

    private fun projection(records: List<Map<String, Any?>>): DeliveryOperationProjection {
        val history = records.filter { it["recordType"] == "delivery-operation" }
        val current = LinkedHashMap<String, Map<String, Any?>>()
        val resolved = history
            .filter { it["resolvedOperationId"] != null && it["state"] in listOf("succeeded", "failed") }
            .mapNotNull { it["resolvedOperationId"]?.toString() }
            .toSet()
        for (record in history) current[record["operationId"]?.toString() ?: ""] = record
        val rollbackHistory = records.filter { it["recordType"] == "rollback-request" }
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
        val payload = serialize(snapshot)
        val updatedAt = (snapshot["updatedAt"] as? String)?.let { parseInstant(it) } ?: Instant.now()
        if (current != null) {
            val updated = jdbc.update(
                """
                UPDATE deliveries
                   SET revision = :revision,
                       stage = :stage,
                       payload = CAST(:payload AS jsonb),
                       updated_at = :updatedAt
                 WHERE organization_id = :organizationId
                   AND workstream_id = :workstreamId
                   AND namespace_id = :namespaceId
                   AND delivery_id = :deliveryId
                """.trimIndent(),
                deliveryParams(scope, namespaceId, deliveryId)
                    .addValue("revision", snapshot["revision"])
                    .addValue("stage", snapshot["stage"])
                    .addValue("payload", payload)
                    .addValue("updatedAt", Timestamp.from(updatedAt)),
            )
            if (updated == 0) return DeliveryWriteResult.failure(DeliveryErrorCodes.REVISION_CONFLICT)
        } else {
            val createdAt = (snapshot["createdAt"] as? String)?.let { parseInstant(it) } ?: Instant.now()
            jdbc.update(
                """
                INSERT INTO deliveries (
                    organization_id, workstream_id, namespace_id, delivery_id,
                    revision, stage, payload, created_at, updated_at
                ) VALUES (
                    :organizationId, :workstreamId, :namespaceId, :deliveryId,
                    :revision, :stage, CAST(:payload AS jsonb), :createdAt, :updatedAt
                )
                """.trimIndent(),
                deliveryParams(scope, namespaceId, deliveryId)
                    .addValue("revision", snapshot["revision"])
                    .addValue("stage", snapshot["stage"])
                    .addValue("payload", payload)
                    .addValue("createdAt", Timestamp.from(createdAt))
                    .addValue("updatedAt", Timestamp.from(updatedAt)),
            )
        }
        appendInternal(scope, namespaceId, deliveryId, listOf(operation, running, succeeded))
        return DeliveryWriteResult.changed(snapshot = snapshot)
    }

    private fun deliveryParams(scope: TenantScope, namespaceId: String, deliveryId: String): MapSqlParameterSource =
        MapSqlParameterSource()
            .addValue("organizationId", scope.organizationId)
            .addValue("workstreamId", scope.workstreamId)
            .addValue("namespaceId", namespaceId)
            .addValue("deliveryId", deliveryId)

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
