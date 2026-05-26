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
        private val OWNER_PRIVATE_ENTITY_TYPES: Set<EntityType> = setOf(EntityType.CASE)
    }

    override fun hasDirectPermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation
    ): Boolean {
        return try {
            when (relation) {
                PermissionRelation.ADMIN -> {
                    permissionNodeRepository.hasAdminPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType.label
                    )
                }
                PermissionRelation.MEMBER -> {
                    permissionNodeRepository.hasMemberOrAdminPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType.label
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
        entityType: EntityType,
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
                    if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                        // Owner-private entities (Case, WZ-32167): namespace ADMIN does NOT
                        // grant transitive access — only a direct ADMIN relation on the
                        // entity counts. Returning false here forces fall-through to
                        // hasDirectPermission which already checked direct ADMIN.
                        false
                    } else {
                        permissionNodeRepository.hasAdminAccessViaNamespace(
                            userId = userId,
                            entityId = entityId,
                            entityLabel = entityType.label
                        )
                    }
                }
                PermissionRelation.MEMBER -> {
                    if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                        // Owner-private entities (Case, FR15, WZ-32167): neither namespace
                        // MEMBER nor namespace ADMIN grants transitive READ. Only a direct
                        // relation on the entity itself (auto-granted at creation) counts.
                        false
                    } else {
                        // Shared entities (AgentConfig, IntegrationConfig, AiProvider,
                        // AiModel): namespace MEMBERs legitimately inherit READ through
                        // the namespace (FR21, FR27, FR32, FR35).
                        permissionNodeRepository.hasReadAccessViaNamespace(
                            userId = userId,
                            entityId = entityId,
                            entityLabel = entityType.label
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
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation
    ) {
        try {
            when (relation) {
                PermissionRelation.ADMIN -> permissionNodeRepository.createAdminPermission(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType.label
                )
                PermissionRelation.MEMBER -> permissionNodeRepository.createMemberPermission(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType.label
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
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation
    ) {
        try {
            when (relation) {
                PermissionRelation.ADMIN -> permissionNodeRepository.deleteAdminPermission(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType.label
                )
                PermissionRelation.MEMBER -> permissionNodeRepository.deleteMemberPermission(
                    userId = userId,
                    entityId = entityId,
                    entityLabel = entityType.label
                )
            }
            logger.info { "Revoked $relation permission from user=$userId on $entityType:$entityId" }
        } catch (e: Exception) {
            logger.error(e) { "Error revoking permission for user=$userId, entity=$entityType:$entityId, relation=$relation" }
            throw e
        }
    }

    override fun listUsersWithPermission(
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation?
    ): List<String> {
        return try {
            when (relation) {
                PermissionRelation.ADMIN -> permissionNodeRepository.findUsersWithAdminPermission(
                    entityId = entityId,
                    entityLabel = entityType.label
                )
                PermissionRelation.MEMBER -> permissionNodeRepository.findUsersWithMemberPermission(
                    entityId = entityId,
                    entityLabel = entityType.label
                )
                null -> permissionNodeRepository.findUsersWithAnyPermission(
                    entityId = entityId,
                    entityLabel = entityType.label
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Error listing users with permission on $entityType:$entityId, relation=$relation" }
            emptyList() // Fail-closed: return empty list on error
        }
    }

    override fun listEntitiesForUser(
        userId: String,
        entityType: EntityType,
        relation: PermissionRelation
    ): List<String> {
        return try {
            // Include both direct and transitive permissions
            when (relation) {
                PermissionRelation.ADMIN -> {
                    if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                        // Owner-private entities (Case, WZ-32167): only direct ADMIN
                        // on the entity counts. Namespace ADMIN is not transitive.
                        permissionNodeRepository.findEntitiesWhereUserIsAdmin(
                            userId = userId,
                            entityLabel = entityType.label
                        )
                    } else if (isNamespaceChildEntity(entityType)) {
                        permissionNodeRepository.findEntitiesWhereUserIsAdminTransitive(
                            userId = userId,
                            entityLabel = entityType.label
                        )
                    } else {
                        permissionNodeRepository.findEntitiesWhereUserIsAdmin(
                            userId = userId,
                            entityLabel = entityType.label
                        )
                    }
                }
                PermissionRelation.MEMBER -> {
                    if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                        // Owner-private entities (Case, FR15, WZ-32167): only a direct
                        // ADMIN or MEMBER relation on the entity itself grants visibility.
                        // Namespace ADMIN does NOT confer transitive access.
                        permissionNodeRepository.findEntitiesWhereUserHasAccess(
                            userId = userId,
                            entityLabel = entityType.label
                        )
                    } else if (isNamespaceChildEntity(entityType)) {
                        permissionNodeRepository.findEntitiesWhereUserHasAccessTransitive(
                            userId = userId,
                            entityLabel = entityType.label
                        )
                    } else {
                        permissionNodeRepository.findEntitiesWhereUserHasAccess(
                            userId = userId,
                            entityLabel = entityType.label
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
        entityType: EntityType,
        ids: Collection<String>,
        relation: PermissionRelation,
    ): Set<String> {
        return try {
            when (relation) {
                PermissionRelation.ADMIN -> permissionNodeRepository.filterIdsWhereUserIsAdmin(
                    userId = userId,
                    entityLabel = entityType.label,
                    ids = ids,
                )
                PermissionRelation.MEMBER -> {
                    if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                        // Owner-private entities (Case, FR15, WZ-32167): only a direct
                        // ADMIN or MEMBER relation on the entity node grants visibility.
                        // Namespace ADMIN does NOT grant transitive READ on cases.
                        permissionNodeRepository.filterIdsWhereUserHasDirectAccess(
                            userId = userId,
                            entityLabel = entityType.label,
                            ids = ids,
                        )
                    } else {
                        permissionNodeRepository.filterIdsWhereUserHasAccess(
                            userId = userId,
                            entityLabel = entityType.label,
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
    private fun isNamespaceChildEntity(entityType: EntityType): Boolean {
        return entityType in setOf(
            EntityType.CASE,
            EntityType.AGENT_CONFIG,
            EntityType.INTEGRATION_CONFIG,
            EntityType.AI_PROVIDER,
            EntityType.AI_MODEL,
        )
    }
}
