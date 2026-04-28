package io.whozoss.agentos.permissions

import mu.KLogging

/**
 * Neo4j implementation of PermissionRepository using the Spring Data Neo4j pattern.
 * This acts as a bridge to the PermissionNodeNeo4jRepository, handling
 * error handling according to the fail-closed security model.
 *
 * IMPORTANT: This implementation follows the correct Spring Data Neo4j pattern,
 * NOT using Driver.session() directly. All Neo4j operations go through the
 * PermissionNodeNeo4jRepository with its @Query annotations.
 */
class Neo4jPermissionRepository(
    private val permissionNodeRepository: PermissionNodeNeo4jRepository
) : PermissionRepository {

    companion object : KLogging() {
        /**
         * Entity types where a namespace MEMBER does NOT gain transitive READ
         * through the parent namespace — only via a direct relation on the
         * entity itself, or through namespace ADMIN transitivity.
         *
         * Rationale (, FR15): these entities are "owner-private" — each
         * one has a creator who is auto-granted ADMIN on creation.
         * Letting every namespace MEMBER see every owner-private entity would
         * break content isolation.
         */
        private val OWNER_PRIVATE_ENTITY_TYPES: Set<String> = setOf("Case")
    }

    override fun hasDirectPermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ): Boolean {
        return try {
            when (relation) {
                PermissionRelation.ADMIN -> {
                    permissionNodeRepository.hasAdminPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType
                    )
                }
                PermissionRelation.MEMBER -> {
                    permissionNodeRepository.hasMemberOrAdminPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error checking direct permission for user=$userId, entity=$entityType:$entityId, relation=$relation" }
            false // Fail-closed: any error returns false
        }
    }

    override fun hasTransitivePermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ): Boolean {
        return try {
            // Only check transitive permissions for namespace child entities
            if (!isNamespaceChildEntity(entityType)) {
                return false
            }

            when (relation) {
                PermissionRelation.ADMIN -> {
                    permissionNodeRepository.hasAdminAccessViaNamespace(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType
                    )
                }
                PermissionRelation.MEMBER -> {
                    if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                        // Owner-private entities (e.g. Case, FR15): a namespace MEMBER
                        // does NOT get transitive READ on children — only a namespace
                        // ADMIN does, or the user with a direct relation on the entity
                        // (handled in hasDirectPermission upstream).
                        permissionNodeRepository.hasAdminAccessViaNamespace(
                            userId = userId,
                            entityId = entityId,
                            entityLabel = entityType
                        )
                    } else {
                        // Shared entities (AgentConfig, IntegrationConfig, AiProvider,
                        // AiModel): namespace MEMBERs legitimately inherit READ through
                        // the namespace (FR21, FR27, FR32, FR35).
                        permissionNodeRepository.hasReadAccessViaNamespace(
                            userId = userId,
                            entityId = entityId,
                            entityLabel = entityType
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error checking transitive permission for user=$userId, entity=$entityType:$entityId, relation=$relation" }
            false // Fail-closed: any error returns false
        }
    }

    override fun grantPermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ) {
        try {
            when (relation) {
                PermissionRelation.ADMIN -> permissionNodeRepository.createAdminPermission(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType
                )
                PermissionRelation.MEMBER -> permissionNodeRepository.createMemberPermission(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType
                )
            }
            logger.info { "Granted $relation permission to user=$userId on $entityType:$entityId" }
        } catch (e: Exception) {
            logger.error(e) { "Error granting permission for user=$userId, entity=$entityType:$entityId, relation=$relation" }
            throw e
        }
    }

    override fun revokePermission(
        userId: String,
        entityType: String,
        entityId: String,
        relation: PermissionRelation
    ) {
        try {
            when (relation) {
                PermissionRelation.ADMIN -> permissionNodeRepository.deleteAdminPermission(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType
                )
                PermissionRelation.MEMBER -> permissionNodeRepository.deleteMemberPermission(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType
                )
            }
            logger.info { "Revoked $relation permission from user=$userId on $entityType:$entityId" }
        } catch (e: Exception) {
            logger.error(e) { "Error revoking permission for user=$userId, entity=$entityType:$entityId, relation=$relation" }
            throw e
        }
    }

    override fun listUsersWithPermission(
        entityType: String,
        entityId: String,
        relation: PermissionRelation?
    ): List<String> {
        return try {
            when (relation) {
                PermissionRelation.ADMIN -> permissionNodeRepository.findUsersWithAdminPermission(
                    entityId = entityId,
                    entityLabel = entityType
                )
                PermissionRelation.MEMBER -> permissionNodeRepository.findUsersWithMemberPermission(
                    entityId = entityId,
                    entityLabel = entityType
                )
                null -> permissionNodeRepository.findUsersWithAnyPermission(
                    entityId = entityId,
                    entityLabel = entityType
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Error listing users with permission on $entityType:$entityId, relation=$relation" }
            emptyList() // Fail-closed: return empty list on error
        }
    }

    override fun listEntitiesForUser(
        userId: String,
        entityType: String,
        relation: PermissionRelation
    ): List<String> {
        return try {
            // Include both direct and transitive permissions
            when (relation) {
                PermissionRelation.ADMIN -> {
                    if (isNamespaceChildEntity(entityType)) {
                        permissionNodeRepository.findEntitiesWhereUserIsAdminTransitive(
                            userId = userId,
                            entityLabel = entityType
                        )
                    } else {
                        permissionNodeRepository.findEntitiesWhereUserIsAdmin(
                            userId = userId,
                            entityLabel = entityType
                        )
                    }
                }
                PermissionRelation.MEMBER -> {
                    if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                        // Owner-private entities: MEMBER transitivity via namespace-MEMBER
                        // is forbidden (FR15). Use the admin-transitive query which gives
                        // direct ADMIN on entity + transitive via namespace ADMIN — the
                        // exact set a user is allowed to "see".
                        permissionNodeRepository.findEntitiesWhereUserIsAdminTransitive(
                            userId = userId,
                            entityLabel = entityType
                        )
                    } else if (isNamespaceChildEntity(entityType)) {
                        permissionNodeRepository.findEntitiesWhereUserHasAccessTransitive(
                            userId = userId,
                            entityLabel = entityType
                        )
                    } else {
                        permissionNodeRepository.findEntitiesWhereUserHasAccess(
                            userId = userId,
                            entityLabel = entityType
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error listing entities for user=$userId, type=$entityType, relation=$relation" }
            emptyList() // Fail-closed: return empty list on error
        }
    }

    override fun filterVisibleIds(
        userId: String,
        entityType: String,
        ids: Collection<String>,
        relation: PermissionRelation,
    ): Set<String> {
        return try {
            when (relation) {
                PermissionRelation.ADMIN -> permissionNodeRepository.filterIdsWhereUserIsAdmin(
                    userId = userId,
                    entityLabel = entityType,
                    ids = ids,
                )
                PermissionRelation.MEMBER -> {
                    if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                        // Owner-private entities (Case, FR15) — MEMBER on the namespace does NOT
                        // grant READ on children. Same rule as listEntitiesForUser:
                        // only direct relation OR transitive via namespace ADMIN counts.
                        permissionNodeRepository.filterIdsWhereUserIsAdmin(
                            userId = userId,
                            entityLabel = entityType,
                            ids = ids,
                        )
                    } else {
                        permissionNodeRepository.filterIdsWhereUserHasAccess(
                            userId = userId,
                            entityLabel = entityType,
                            ids = ids,
                        )
                    }
                }
            }.toSet()
        } catch (e: Exception) {
            logger.error(e) { "Error filtering visible ids for user=$userId, type=$entityType, relation=$relation" }
            emptySet() // Fail-closed: return empty set on error
        }
    }

    /**
     * Checks if the entity type is a child of Namespace in the hierarchy.
     * These entities support transitive permissions through their parent namespace.
     */
    private fun isNamespaceChildEntity(entityType: String): Boolean {
        return entityType in setOf(
            "Case",
            "AgentConfig",
            "IntegrationConfig",
            "AiProvider",
            "AiModel"
        )
    }
}
