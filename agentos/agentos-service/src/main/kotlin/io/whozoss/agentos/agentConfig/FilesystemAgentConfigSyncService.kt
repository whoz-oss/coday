package io.whozoss.agentos.agentConfig

import mu.KLogging
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Synchronises filesystem-defined [AgentConfig]s into Neo4j so they can be addressed
 * by other entities (e.g. [io.whozoss.agentos.scheduledPrompt.ScheduledPrompt],
 * [io.whozoss.agentos.userGroup.UserGroup]) via stable Neo4j relationships.
 *
 * ### Strategy
 *
 * Called lazily by [FilesystemAgentConfigRepository] after each cache reload (at most once
 * per TTL window per namespace). Receives the full list of currently-live filesystem agents
 * for a namespace and:
 *
 * 1. **Upserts** each live agent into Neo4j via [Neo4jAgentConfigRepository.save], preserving
 *    the deterministic UUID derived from the agent name. The node carries `fileOrigin = true`
 *    and `enabled = true` (filesystem agents are always considered published).
 * 2. **Soft-deletes** any Neo4j node previously upserted from the filesystem
 *    (`fileOrigin = true`) whose name no longer appears in the live set — this covers
 *    file deletion and renaming without leaving orphaned nodes that would keep a
 *    ScheduledPrompt or UserGroup pointing at a gone agent.
 *
 * ### Why [Neo4jAgentConfigRepository] and not [AgentConfigRepository]
 *
 * The injected [AgentConfigRepository] bean is [FilesystemAgentConfigRepository], the composite
 * decorator. Calling [AgentConfigRepository.findByParent] on it would merge the filesystem
 * agents back in and create a feedback loop. The [Neo4jAgentConfigRepository] delegate
 * addresses Neo4j directly, which is what we need here.
 *
 * ### Collision rule
 *
 * A persisted API-managed agent (`fileOrigin = false`) and a filesystem agent sharing the
 * same name within a namespace is a configuration error. The API-managed agent wins in all
 * read paths ([FilesystemAgentConfigRepository] applies the same collision rule). This service
 * skips the upsert for a filesystem agent whose deterministic UUID already resolves to an
 * API-managed node to avoid overwriting its `fileOrigin = false` flag.
 */
@Service
class FilesystemAgentConfigSyncService(
    private val neo4jRepository: Neo4jAgentConfigRepository,
) : FilesystemAgentSyncCallback {
    override fun sync(
        namespaceId: UUID,
        liveAgents: List<AgentConfig>,
    ) {
        val liveNames = liveAgents.map { it.name.lowercase() }.toSet()

        // Fetch only the file-origin nodes currently persisted for this namespace.
        // We use the delegate directly to avoid the filesystem feedback loop.
        val existingFileOriginNodes =
            neo4jRepository
                .findByParent(namespaceId)
                .filter { it.fileOrigin }

        // --- Soft-delete stale file-origin nodes ---
        // A file-origin node is stale when its name no longer matches any live filesystem agent.
        // We match by name (case-insensitive) because the deterministic UUID is derived from the
        // name — a rename produces a new UUID, making the old node stale by name, not by id.
        existingFileOriginNodes
            .filter { it.name.lowercase() !in liveNames }
            .forEach { stale ->
                logger.info {
                    "[FilesystemAgentConfigSyncService] Soft-deleting stale file-origin agent " +
                        "'${stale.name}' (${stale.metadata.id}) from namespace $namespaceId"
                }
                neo4jRepository.delete(stale.metadata.id)
            }

        // --- Upsert live filesystem agents ---
        // Skip agents whose deterministic id already belongs to an API-managed node
        // (fileOrigin = false) — the collision rule preserves the API-managed agent.
        //
        // existingFileOriginNodes is also used to carry the SDN @Version of already-persisted
        // file-origin nodes into the upsert. Without the version, SDN treats version=null as a
        // new entity and attempts an INSERT, which fails with OptimisticLockingFailureException
        // on the second sync (after the cache TTL expires) when the node already exists in Neo4j.
        val existingFileOriginById = existingFileOriginNodes.associateBy { it.metadata.id }

        val liveIds = liveAgents.map { it.metadata.id }.toSet()
        val existingApiManagedIds =
            neo4jRepository
                .findByIds(liveIds)
                .filter { !it.fileOrigin }
                .map { it.metadata.id }
                .toSet()

        liveAgents
            .filter { it.metadata.id !in existingApiManagedIds }
            .forEach { agent ->
                // Preserve the persisted version so SDN performs an UPDATE rather than an INSERT.
                val existingVersion = existingFileOriginById[agent.metadata.id]?.metadata?.version
                val toSave = agent.copy(
                    namespaceId = namespaceId,
                    metadata = agent.metadata.copy(version = existingVersion),
                )
                neo4jRepository.save(toSave)
                logger.debug {
                    "[FilesystemAgentConfigSyncService] Upserted file-origin agent " +
                        "'${agent.name}' (${agent.metadata.id}) into namespace $namespaceId"
                }
            }
    }

    companion object : KLogging()
}
