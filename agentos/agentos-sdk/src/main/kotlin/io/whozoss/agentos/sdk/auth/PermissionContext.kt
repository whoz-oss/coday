package io.whozoss.agentos.sdk.auth

import java.util.UUID

data class PermissionContext(
    val userId: UUID,
    val isRoot: Boolean,
    val namespaceId: UUID? = null,
    val namespaceRole: NamespaceRole? = null,
    val caseId: UUID? = null,
    val caseRole: CaseRole? = null,
    val toolRestriction: ToolRestriction? = null,
)
