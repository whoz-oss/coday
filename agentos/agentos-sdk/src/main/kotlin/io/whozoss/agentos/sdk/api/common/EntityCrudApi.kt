package io.whozoss.agentos.sdk.api.common

import java.util.UUID

/**
 * Base HTTP contract shared by all entity CRUD APIs in AgentOS.
 *
 * Each concrete `*Api` interface extends this one, supplying its own [DtoType].
 * Feign clients and the service-layer controllers both implement the same hierarchy,
 * so the five methods below are guaranteed to exist on every entity endpoint.
 *
 * What belongs here: operations that are structurally identical across every entity
 * type — single-fetch, batch-fetch, create, update, delete.
 *
 * What does NOT belong here: entity-specific queries (`listByParent`, `listAll`,
 * `search`, `list` with scope params, etc.). Those stay on the concrete interface.
 */
interface EntityCrudApi<DtoType> {

    fun getById(id: UUID): DtoType

    fun getByIds(request: GetByIdsRequest): List<DtoType>

    fun create(resource: DtoType): DtoType

    fun update(id: UUID, resource: DtoType): DtoType

    fun delete(id: UUID)
}
