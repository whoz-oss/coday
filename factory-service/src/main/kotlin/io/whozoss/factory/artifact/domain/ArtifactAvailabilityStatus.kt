package io.whozoss.factory.artifact.domain

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * Orthogonal availability dimension of an artifact: *where the bytes are*.
 *
 * Ported from `ArtifactAvailabilityStatus` in
 * `factory/src/ports/artifact/artifact-store.ts`. The TS union only surfaced
 * `available | purged | archived`, but the V6 `artifacts.availability_status`
 * CHECK also admits `pending`, `uploading` and `unavailable`, so the full set is
 * modelled here. Wire values are the lower-case strings the Node Factory emits.
 */
enum class ArtifactAvailabilityStatus(val wireValue: String) {
    AVAILABLE("available"),
    PURGED("purged"),
    ARCHIVED("archived"),
    PENDING("pending"),
    UPLOADING("uploading"),
    UNAVAILABLE("unavailable"),
    ;

    @JsonValue
    fun jsonValue(): String = wireValue

    companion object {
        /** Resolves a wire value, defaulting to [ARCHIVED] for unknown inputs. */
        @JvmStatic
        @JsonCreator
        fun fromWire(value: String?): ArtifactAvailabilityStatus =
            entries.firstOrNull { it.wireValue == value } ?: ARCHIVED
    }
}
