package io.whozoss.factory.artifact.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Orthogonal retention dimension of an artifact: *is the compliance window
 * still open?*. Ported from `ArtifactRetentionStatus` in
 * `factory/src/ports/artifact/artifact-store.ts`.
 */
enum class ArtifactRetentionStatus(val wireValue: String) {
    ACTIVE("active"),
    EXPIRED("expired"),
    ;

    @JsonValue
    fun jsonValue(): String = wireValue

    companion object {
        /** Resolves a wire value, defaulting to [ACTIVE] for unknown inputs. */
        @JvmStatic
        @JsonCreator
        fun fromWire(value: String?): ArtifactRetentionStatus =
            if (value == EXPIRED.wireValue) EXPIRED else ACTIVE
    }
}
