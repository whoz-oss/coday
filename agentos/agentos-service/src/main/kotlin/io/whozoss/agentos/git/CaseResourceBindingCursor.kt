package io.whozoss.agentos.git

import java.time.Instant
import java.util.UUID

/** Immutable position in a binding sweep, independent of the continued existence of its row. */
data class CaseResourceBindingCursor(
    val created: Instant,
    val id: UUID,
) {
    companion object {
        fun after(binding: CaseResourceBinding): CaseResourceBindingCursor =
            CaseResourceBindingCursor(binding.metadata.created, binding.id)
    }
}
