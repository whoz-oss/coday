package io.whozoss.factory.lease.domain

/**
 * Ordered lifecycle states a work-unit lease can take (V6 `work_unit_leases.status`).
 *
 * Port of `WORK_UNIT_LEASE_STATUSES` in `factory/src/domain/lease/lease.ts`.
 */
enum class LeaseStatus(val dbValue: String) {
    ACTIVE("active"),
    RELEASED("released"),
    EXPIRED("expired");

    companion object {
        private val BY_DB_VALUE: Map<String, LeaseStatus> = entries.associateBy { it.dbValue }

        /** Parse a persisted status, case-insensitively. */
        fun fromDbValue(value: String): LeaseStatus =
            BY_DB_VALUE[value.lowercase()] ?: throw IllegalArgumentException("Unknown lease status: $value")
    }
}

/**
 * Machine-readable reasons for an `active` -> `expired` transition.
 *
 * Port of `LEASE_EXPIRY_REASONS` in `factory/src/domain/lease/lease.ts`.
 */
object LeaseExpiryReasons {
    const val HEARTBEAT_TIMEOUT = "heartbeat_timeout"
    const val WORKER_LOST = "worker_lost"
    const val RECLAIMED = "reclaimed"
}
