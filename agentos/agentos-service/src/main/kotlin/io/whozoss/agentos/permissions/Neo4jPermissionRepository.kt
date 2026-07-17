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
    private val permissionNodeRepository: PermissionNodeNeo4jRepository,
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

        /**
         * Entity types that can exist in "platform scope" (namespaceId = null) and
         * for which platform-scoped instances are readable by any authenticated user.
         *
         * When [hasTransitivePermission] is called with READ on one of these types and
         * the entity has no BELONGS_TO edge to any Namespace, the check returns true
         * so that every authenticated caller can read platform-level content without
         * requiring an explicit permission grant.
         *
         * Platform-scoped instances of these types carry no BELONGS_TO edge; the
         * absence of that edge is the signal used by the Cypher query
         * [PermissionNodeNeo4jRepository.isPlatformScoped].
         *
         * Covers: [EntityType.PROMPT], [EntityType.AGENT_CONFIG],
         * [EntityType.INTEGRATION_CONFIG], [EntityType.AI_PROVIDER], [EntityType.AI_MODEL].
         */
        private val PLATFORM_SCOPABLE_ENTITY_TYPES: Set<EntityType> = setOf(
                EntityType.PROMPT,
                EntityType.AGENT_CONFIG,
                EntityType.INTEGRATION_CONFIG,
                EntityType.AI_PROVIDER,
                EntityType.AI_MODEL,
            )
    }

    override fun hasDirectPermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation,
    ): Boolean =
        try {
            when (relation) {
                PermissionRelation.ADMIN -> {
                    permissionNodeRepository.hasAdminPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType.label,
                    )
                }

                PermissionRelation.MEMBER -> {
                    permissionNodeRepository.hasMemberOrAdminPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType.label,
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error checking direct permission for user=$userId, entity=$entityType:$entityId, relation=$relation" }
            false // Fail-closed: any error returns false
        }

    override fun hasTransitivePermission(
        userId: String,
        entityType: EntityType,
        entityId: String,
        relation: PermissionRelation,
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
                        entityLabel = entityType.label,
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
                            entityLabel = entityType.label,
                        )
                    } else {
                        // Shared entities (AgentConfig, IntegrationConfig, AiProvider,
                        // AiModel, Prompt): namespace MEMBERs legitimately inherit READ
                        // through the namespace (FR21, FR27, FR32, FR35).
                        //
                        // For platform-scopable types (e.g. Prompt), also grant READ
                        // when the entity has no BELONGS_TO edge — i.e. it is
                        // platform-scoped and readable by any authenticated user.
                        val hasNamespaceAccess = permissionNodeRepository.hasReadAccessViaNamespace(
                            userId = userId,
                            entityId = entityId,
                            entityLabel = entityType.label,
                        )
                        hasNamespaceAccess ||
                            (entityType in PLATFORM_SCOPABLE_ENTITY_TYPES &&
                                permissionNodeRepository.isPlatformScoped(
                                    entityId = entityId,
                                    entityLabel = entityType.label,
                                ))
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
        relation: PermissionRelation,
    ) {
        try {
            when (relation) {
                PermissionRelation.ADMIN -> {
                    permissionNodeRepository.createAdminPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType.label,
                    )
                }

                PermissionRelation.MEMBER -> {
                    permissionNodeRepository.createMemberPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType.label,
                    )
                }
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
        relation: PermissionRelation,
    ) {
        try {
            when (relation) {
                PermissionRelation.ADMIN -> {
                    permissionNodeRepository.deleteAdminPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType.label,
                    )
                }

                PermissionRelation.MEMBER -> {
                    permissionNodeRepository.deleteMemberPermission(
                        userId = userId,
                        entityId = entityId,
                        entityLabel = entityType.label,
                    )
                }
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
        relation: PermissionRelation?,
    ): List<String> =
        try {
            when (relation) {
                PermissionRelation.ADMIN -> {
                    permissionNodeRepository.findUsersWithAdminPermission(
                        entityId = entityId,
                        entityLabel = entityType.label,
                    )
                }

                PermissionRelation.MEMBER -> {
                    permissionNodeRepository.findUsersWithMemberPermission(
                        entityId = entityId,
                        entityLabel = entityType.label,
                    )
                }

                null -> {
                    permissionNodeRepository.findUsersWithAnyPermission(
                        entityId = entityId,
                        entityLabel = entityType.label,
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error listing users with permission on $entityType:$entityId, relation=$relation" }
            emptyList() // Fail-closed: return empty list on error
        }

    // TODO: used only for namespace, to un-generalize ?
    override fun listEntitiesForUser(
        userId: String,
        entityType: EntityType,
        relation: PermissionRelation,
    ): List<String> =
        try {
            // Include both direct and transitive permissions
            when (relation) {
                PermissionRelation.ADMIN -> {
                    if (isNamespaceChildEntity(entityType)) {
                        permissionNodeRepository.findEntitiesWhereUserIsAdminTransitive(
                            userId = userId,
                            entityLabel = entityType.label,
                        )
                    } else {
                        permissionNodeRepository.findEntitiesWhereUserIsAdmin(
                            userId = userId,
                            entityLabel = entityType.label,
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
                            entityLabel = entityType.label,
                        )
                    } else if (isNamespaceChildEntity(entityType)) {
                        permissionNodeRepository.findEntitiesWhereUserHasAccessTransitive(
                            userId = userId,
                            entityLabel = entityType.label,
                        )
                    } else {
                        permissionNodeRepository.findEntitiesWhereUserHasAccess(
                            userId = userId,
                            entityLabel = entityType.label,
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error listing entities for user=$userId, type=$entityType, relation=$relation" }
            emptyList() // Fail-closed: return empty list on error
        }

    override fun filterVisibleIds(
        userId: String,
        entityType: EntityType,
        ids: Collection<String>,
        relation: PermissionRelation,
    ): Set<String> {
        val checkPlatform = isNamespaceChildEntity(entityType)
        return try {
            when (relation) {
                PermissionRelation.ADMIN -> {
                    permissionNodeRepository.filterIdsWhereUserIsAdmin(
                        userId = userId,
                        entityLabel = entityType.label,
                        ids = ids,
                        checkPlatform = checkPlatform,
                    )
                }

                PermissionRelation.MEMBER -> {
                    if (entityType in OWNER_PRIVATE_ENTITY_TYPES) {
                        // Owner-private entities (Case, FR15) — MEMBER on the namespace does NOT
                        // grant READ on children. Same rule as listEntitiesForUser:
                        // only direct relation OR transitive via namespace ADMIN counts.
                        permissionNodeRepository.filterIdsWhereUserIsAdmin(
                            userId = userId,
                            entityLabel = entityType.label,
                            ids = ids,
                            checkPlatform = checkPlatform,
                        )
                    } else {
                        // Namespace-child entities may have platform-scoped instances
                        // (namespaceId IS NULL). Use the variant query that includes a
                        // third UNION branch for those — safe because all types in
                        // isNamespaceChildEntity have namespaceId as a node property.
                        permissionNodeRepository.filterIdsWhereUserHasAccess(
                            userId = userId,
                            entityLabel = entityType.label,
                            ids = ids,
                            checkPlatform = checkPlatform,
                        )
                    }
                }
            }.toSet()
        } catch (e: Exception) {
            logger.error(e) { "Error filtering visible ids for user=$userId, type=$entityType, relation=$relation" }
            emptySet() // Fail-closed: return empty set on error
        }
    }

    override fun promoteMemberToAdmin(userId: String, entityType: EntityType, entityId: String): Boolean =
        try {
            permissionNodeRepository.promoteMemberToAdmin(
                userId = userId,
                entityId = entityId,
                entityLabel = entityType.label,
            ) > 0
        } catch (e: Exception) {
            logger.error(e) { "Error promoting MEMBER to ADMIN for user=$userId on $entityType:$entityId" }
            throw e
        }

    override fun demoteAdminToMember(userId: String, entityType: EntityType, entityId: String): Boolean =
        try {
            permissionNodeRepository.demoteAdminToMember(
                userId = userId,
                entityId = entityId,
                entityLabel = entityType.label,
            ) > 0
        } catch (e: Exception) {
            logger.error(e) { "Error demoting ADMIN to MEMBER for user=$userId on $entityType:$entityId" }
            throw e
        }

    /**
     * Checks if the entity type is a child of Namespace in the hierarchy.
     * These entities support transitive permissions through their parent namespace.
     *
     * [EntityType.PROMPT] is included because namespace-scoped prompts carry a
     * BELONGS_TO edge to their parent Namespace and must be readable by namespace
     * MEMBERs via transitive permission (the same rule as AgentConfig, etc.).
     */
    private fun isNamespaceChildEntity(entityType: EntityType): Boolean =
        entityType in
            setOf(
                EntityType.CASE,
                EntityType.AGENT_CONFIG,
                EntityType.INTEGRATION_CONFIG,
                EntityType.AI_PROVIDER,
                EntityType.AI_MODEL,
                EntityType.USER_GROUP,
                EntityType.PROMPT,
            )
}
