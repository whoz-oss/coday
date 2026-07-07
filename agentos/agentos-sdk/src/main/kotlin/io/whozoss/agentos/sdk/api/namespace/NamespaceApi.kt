package io.whozoss.agentos.sdk.api.namespace

import io.whozoss.agentos.sdk.api.common.EntityCrudApi
import io.whozoss.agentos.sdk.api.common.GetByIdsRequest
import java.util.UUID

/**
 * HTTP API contract for Namespace entities.
 *
 * Implemented by `NamespaceController` in agentos-service. External consumers
 * implement this interface as a Feign client, adding their own `@FeignClient` and
 * routing annotations. AgentOS does not prescribe the client technology or configuration.
 *
 * Authorization summary (enforced server-side, not expressed here):
 * - [listAll]: any authenticated user — response is permission-filtered
 * - [create], [delete]: SUPER_ADMIN only
 * - [update]: namespace ADMIN
 * - [getById], [getByIds]: namespace MEMBER (transitive)
 */
interface NamespaceApi : EntityCrudApi<NamespaceDto> {

    /** GET /api/namespaces — list all namespaces visible to the caller. */
    fun listAll(): List<NamespaceListItem>

    /**
     * POST /api/namespaces/by-external-ids — look up namespaces by external identifier.
     *
     * Returns only namespaces the caller has READ on. Super-admins receive all matches.
     * Unknown external IDs are silently omitted.
     */
    fun listByExternalIds(externalIds: List<String>): List<NamespaceDto>
}
