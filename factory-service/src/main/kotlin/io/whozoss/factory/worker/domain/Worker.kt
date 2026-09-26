package io.whozoss.factory.worker.domain

import java.time.Instant

/**
 * Aggregate root of a worker node.
 *
 * Maps 1:1 to the organization-scoped V6 `workers` row extended by V7
 * (`last_heartbeat_at`, `protocol_version`, `capabilities`). Port of the
 * `Worker` interface in `factory/src/domain/worker.ts`.
 *
 * The `payload` is kept as its raw JSON text and `capabilities` as the declared
 * capability keys; both are persisted into JSONB columns.
 */
data class Worker(
    val organizationId: String,
    val workerId: String,
    val workerType: String,
    val status: WorkerState = WorkerState.OFFLINE,
    val revision: Int = 1,
    val lastHeartbeatAt: Instant? = null,
    val protocolVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val payload: String = "{}",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
