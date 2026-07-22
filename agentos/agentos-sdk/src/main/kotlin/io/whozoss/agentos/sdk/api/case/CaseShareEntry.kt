package io.whozoss.agentos.sdk.api.case

import java.util.UUID

data class CaseShareEntry(
    val userId: UUID,
    val role: CaseRole?,  // null = revoke
)
