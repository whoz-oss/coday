package io.whozoss.factory.workstream.domain

import io.whozoss.factory.error.BadRequestException

/**
 * Lifecycle status of a workstream registry entry.
 *
 * The database values are the lowercase wire vocabulary: `active`, `paused`,
 * `archived`.
 */
enum class WorkstreamStatus(val dbValue: String) {
    ACTIVE("active"),
    PAUSED("paused"),
    ARCHIVED("archived"),
    ;

    companion object {
        /** Parse a persisted/requested status, case-insensitively. */
        fun fromDbValue(value: String): WorkstreamStatus =
            entries.firstOrNull { it.dbValue == value.trim().lowercase() }
                ?: throw BadRequestException(
                    "Unknown workstream status: $value (expected active, paused or archived)",
                    mapOf("code" to "INVALID_WORKSTREAM_STATUS"),
                )
    }
}
