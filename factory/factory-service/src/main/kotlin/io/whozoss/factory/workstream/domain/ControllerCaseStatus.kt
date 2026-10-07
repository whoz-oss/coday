package io.whozoss.factory.workstream.domain

import io.whozoss.factory.error.BadRequestException

/**
 * Lifecycle status of a controller case execution.
 *
 * At most one case is `active` per workstream at any instant; every previous
 * case is `archived` and immutable. The database values are the lowercase
 * wire vocabulary: `active`, `archived`.
 */
enum class ControllerCaseStatus(val dbValue: String) {
    ACTIVE("active"),
    ARCHIVED("archived"),
    ;

    companion object {
        /** Parse a persisted/requested status, case-insensitively. */
        fun fromDbValue(value: String): ControllerCaseStatus =
            entries.firstOrNull { it.dbValue == value.trim().lowercase() }
                ?: throw BadRequestException(
                    "Unknown controller case status: $value (expected active or archived)",
                    mapOf("code" to "INVALID_CONTROLLER_CASE_STATUS"),
                )
    }
}
