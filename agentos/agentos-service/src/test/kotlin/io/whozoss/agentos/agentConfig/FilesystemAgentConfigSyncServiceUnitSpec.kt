package io.whozoss.agentos.agentConfig

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.dao.OptimisticLockingFailureException
import java.util.UUID

class FilesystemAgentConfigSyncServiceUnitSpec : StringSpec({

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    fun makeNeo4jRepo() = mockk<Neo4jAgentConfigRepository>(relaxed = true)

    fun filesystemAgent(
        name: String,
        namespaceId: UUID? = null,
        version: Long? = null,
    ) = AgentConfig(
        metadata = EntityMetadata(
            id = UUID.nameUUIDFromBytes("filesystem-agent:$name".toByteArray()),
            version = version,
        ),
        fileOrigin = true,
        namespaceId = namespaceId,
        name = name,
        enabled = true,
    )

    fun persistedFileOriginAgent(
        name: String,
        namespaceId: UUID,
        version: Long = 0L,
    ) = filesystemAgent(name, namespaceId, version)

    fun apiManagedAgent(
        name: String,
        namespaceId: UUID,
    ) = AgentConfig(
        metadata = EntityMetadata(id = UUID.nameUUIDFromBytes("filesystem-agent:$name".toByteArray())),
        fileOrigin = false,
        namespaceId = namespaceId,
        name = name,
    )

    val namespaceId: UUID = UUID.randomUUID()

    // -------------------------------------------------------------------------
    // Upsert: first sync (INSERT path)
    // -------------------------------------------------------------------------

    "sync upserts each live filesystem agent into Neo4j" {
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns emptyList()
        every { neo4j.findByIds(any()) } returns emptyList()

        val agent = filesystemAgent("Dev")
        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, listOf(agent))

        verify(exactly = 1) { neo4j.save(any()) }
    }

    "sync sets namespaceId on the upserted agent" {
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns emptyList()
        every { neo4j.findByIds(any()) } returns emptyList()
        val savedSlot = slot<AgentConfig>()
        every { neo4j.save(capture(savedSlot)) } answers { savedSlot.captured }

        val agent = filesystemAgent("Dev", namespaceId = null)
        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, listOf(agent))

        savedSlot.captured.namespaceId shouldBe namespaceId
    }

    // -------------------------------------------------------------------------
    // Upsert: subsequent sync (UPDATE path — version carry-over)
    // -------------------------------------------------------------------------

    "sync carries over the persisted @Version to avoid OptimisticLockingFailureException on second sync" {
        val existingVersion = 3L
        val alreadySynced = persistedFileOriginAgent("Dev", namespaceId, version = existingVersion)
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns listOf(alreadySynced)
        every { neo4j.findByIds(any()) } returns emptyList()
        val savedSlot = slot<AgentConfig>()
        every { neo4j.save(capture(savedSlot)) } answers { savedSlot.captured }

        val liveAgent = filesystemAgent("Dev") // version = null (fresh from YAML parser)
        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, listOf(liveAgent))

        savedSlot.captured.metadata.version shouldBe existingVersion
    }

    "sync uses version=null (INSERT) when the agent has never been synced before" {
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns emptyList()
        every { neo4j.findByIds(any()) } returns emptyList()
        val savedSlot = slot<AgentConfig>()
        every { neo4j.save(capture(savedSlot)) } answers { savedSlot.captured }

        val agent = filesystemAgent("Dev")
        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, listOf(agent))

        savedSlot.captured.metadata.version shouldBe null
    }

    // -------------------------------------------------------------------------
    // Soft-delete: stale file-origin nodes
    // -------------------------------------------------------------------------

    "sync soft-deletes a file-origin node whose name is no longer in the live set" {
        val stale = persistedFileOriginAgent("OldAgent", namespaceId)
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns listOf(stale)
        every { neo4j.findByIds(any()) } returns emptyList()

        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, emptyList())

        verify(exactly = 1) { neo4j.delete(stale.metadata.id) }
    }

    "sync does not soft-delete a file-origin node that is still live" {
        val live = persistedFileOriginAgent("Dev", namespaceId)
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns listOf(live)
        every { neo4j.findByIds(any()) } returns emptyList()

        val liveAgent = filesystemAgent("Dev")
        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, listOf(liveAgent))

        verify(exactly = 0) { neo4j.delete(any()) }
    }

    "sync stale-name detection is case-insensitive" {
        // Node stored as "Dev", live set has "dev" — should NOT be treated as stale
        val synced = persistedFileOriginAgent("Dev", namespaceId)
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns listOf(synced)
        every { neo4j.findByIds(any()) } returns emptyList()

        val liveAgent = filesystemAgent("dev")
        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, listOf(liveAgent))

        verify(exactly = 0) { neo4j.delete(any()) }
    }

    // -------------------------------------------------------------------------
    // Collision rule: API-managed nodes must not be overwritten
    // -------------------------------------------------------------------------

    "sync skips upsert when the deterministic id belongs to an API-managed node" {
        val apiAgent = apiManagedAgent("Dev", namespaceId)
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns emptyList()
        every { neo4j.findByIds(any()) } returns listOf(apiAgent)

        val liveAgent = filesystemAgent("Dev")
        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, listOf(liveAgent))

        verify(exactly = 0) { neo4j.save(any()) }
    }

    "sync does not soft-delete an API-managed node even when its name is absent from the live set" {
        // fileOrigin=false nodes are never touched by the purge logic
        val apiAgent = apiManagedAgent("Dev", namespaceId)
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns listOf(apiAgent)
        every { neo4j.findByIds(any()) } returns emptyList()

        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, emptyList())

        verify(exactly = 0) { neo4j.delete(any()) }
    }

    // -------------------------------------------------------------------------
    // Error resilience
    // -------------------------------------------------------------------------

    "sync swallows Neo4j exceptions and does not propagate them to the caller" {
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } throws RuntimeException("Neo4j unavailable")

        // Must not throw
        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, listOf(filesystemAgent("Dev")))
    }

    "sync swallows OptimisticLockingFailureException from concurrent syncs" {
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns emptyList()
        every { neo4j.findByIds(any()) } returns emptyList()
        every { neo4j.save(any()) } throws OptimisticLockingFailureException("concurrent write")

        // Must not throw
        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, listOf(filesystemAgent("Dev")))
    }

    // -------------------------------------------------------------------------
    // Empty live set
    // -------------------------------------------------------------------------

    "sync with empty live set purges all file-origin nodes and saves nothing" {
        val stale1 = persistedFileOriginAgent("OldAgent1", namespaceId)
        val stale2 = persistedFileOriginAgent("OldAgent2", namespaceId)
        val neo4j = makeNeo4jRepo()
        every { neo4j.findByParent(namespaceId) } returns listOf(stale1, stale2)
        every { neo4j.findByIds(any()) } returns emptyList()

        FilesystemAgentConfigSyncService(neo4j).sync(namespaceId, emptyList())

        verify(exactly = 1) { neo4j.delete(stale1.metadata.id) }
        verify(exactly = 1) { neo4j.delete(stale2.metadata.id) }
        verify(exactly = 0) { neo4j.save(any()) }
    }
})
