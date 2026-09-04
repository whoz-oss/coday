package io.whozoss.agentos.skill

import io.whozoss.agentos.persistence.Neo4jChildLinkService
import mu.KLogging
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Neo4j-backed implementation of [SkillRepository].
 *
 * [save] is `@Transactional` so the entity write and the BELONGS_TO link are part of a single
 * Neo4j transaction. Platform-level skills (`namespaceId == null`) skip the link step.
 *
 * [findByParent] delegates to [findByNamespaceId] by convention.
 */
open class Neo4jSkillRepository(
    private val neo4jRepository: SkillNodeNeo4jRepository,
    private val childLinkService: Neo4jChildLinkService,
) : SkillRepository {
    @Transactional
    open override fun save(entity: Skill): Skill =
        neo4jRepository
            .save(SkillNode.fromDomain(entity))
            .also { savedNode ->
                entity.namespaceId?.let { nsId ->
                    childLinkService.link("Skill", savedNode.id, "Namespace", nsId.toString())
                }
            }.toDomain()
            .also {
                logger.debug {
                    "[Neo4jSkillRepository] Saved skill ${it.id} ('${entity.name}') " +
                        "scope=(namespaceId=${entity.namespaceId})"
                }
            }

    override fun findByIds(
        ids: Collection<UUID>,
        withRemoved: Boolean,
    ): List<Skill> =
        neo4jRepository
            .findAllById(ids.map { it.toString() })
            .filter { withRemoved || it.removed != true }
            .map { it.toDomain() }

    override fun findByParent(parentId: UUID): List<Skill> = findByNamespaceId(parentId)

    override fun findByNamespaceId(namespaceId: UUID): List<Skill> =
        neo4jRepository
            .findActiveByNamespaceId(namespaceId.toString())
            .map { it.toDomain() }

    override fun findPlatform(): List<Skill> =
        neo4jRepository
            .findActivePlatform()
            .map { it.toDomain() }

    override fun findByNameInNamespace(
        namespaceId: UUID?,
        name: String,
    ): Skill? =
        neo4jRepository
            .findActiveByDoubleKey(SkillNode.computeDoubleKey(namespaceId, name))
            ?.toDomain()

    override fun delete(id: UUID): Boolean =
        neo4jRepository
            .findByIdOrNull(id.toString())
            ?.takeIf { it.removed != true }
            ?.let { node ->
                neo4jRepository.save(
                    node.copy(
                        removed = true,
                        doubleKey = SkillNode.tombstoneDoubleKey(node.id),
                    ),
                )
                logger.debug { "[Neo4jSkillRepository] Soft-deleted skill $id" }
                true
            } ?: false

    @Transactional
    open override fun deleteByParent(parentId: UUID): Int {
        val active = neo4jRepository.findActiveByNamespaceId(parentId.toString())
        neo4jRepository.saveAll(
            active.map {
                it.copy(
                    removed = true,
                    doubleKey = SkillNode.tombstoneDoubleKey(it.id),
                )
            },
        )
        logger.debug { "[Neo4jSkillRepository] Soft-deleted ${active.size} skills under namespace $parentId" }
        return active.size
    }

    companion object : KLogging()
}
