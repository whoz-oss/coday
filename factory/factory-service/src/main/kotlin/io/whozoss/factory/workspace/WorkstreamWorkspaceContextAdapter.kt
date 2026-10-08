package io.whozoss.factory.workspace

import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workstream.WorkstreamService
import org.springframework.stereotype.Component

/**
 * Read-only [WorkspaceContextPort] resolving the run's workstream through the
 * Factory workstream registry.
 *
 * `WorkstreamService.findDomain` is a pure scoped read: it answers `null` when the
 * workstream is absent in the caller's tenant scope, which the pre-check reports as
 * [WorkspacePrecheckCodes.WORKSTREAM_UNRESOLVABLE].
 */
@Component
class WorkstreamWorkspaceContextAdapter(
    private val workstreamService: WorkstreamService,
) : WorkspaceContextPort {

    override fun isWorkstreamResolvable(scope: TenantScope): Boolean =
        workstreamService.findDomain(scope, scope.workstreamId) != null
}
