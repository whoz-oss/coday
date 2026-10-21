package io.whozoss.factory.workstream.domain

import io.whozoss.factory.error.BadRequestException

/** Canonical namespace identity shared by service and persistence write boundaries. */
object WorkstreamNamespace {
    private val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun require(value: String?): String {
        val normalized = value?.trim()?.lowercase()
        if (normalized == null || !uuid.matches(normalized)) {
            throw BadRequestException("A namespace UUID is required", mapOf("code" to "INVALID_WORKSTREAM_NAMESPACE"))
        }
        return normalized
    }
}
