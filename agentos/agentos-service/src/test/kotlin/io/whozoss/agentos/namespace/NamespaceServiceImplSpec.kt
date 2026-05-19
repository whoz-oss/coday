package io.whozoss.agentos.namespace

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID
import java.util.UUID.randomUUID

/**
 * Unit tests for [NamespaceServiceImpl.findIdsVisibleTo] — the thin typed
 * wrapper added to encapsulate the `"Namespace"` entityType literal and the
 * `String → UUID` conversion that previously lived in `NamespaceController.listAll`.
 *
 * CRUD operations of [NamespaceServiceImpl] are pure delegates to the repository
 * and are covered transitively by `Abstract*PersistenceSpec` against a real
 * Neo4j harness — no need to duplicate them here.
 */
class NamespaceServiceImplSpec :
    StringSpec({
        val namespaceRepository = mockk<NamespaceRepository>(relaxed = true)
        val permissionService = mockk<PermissionService>()
        val agentConfigRepository = mockk<AgentConfigRepository>(relaxed = true)
        val service = NamespaceServiceImpl(namespaceRepository, permissionService, agentConfigRepository)

        val userId = randomUUID().toString()

        beforeTest { clearAllMocks() }

        "findIdsVisibleTo delegates to PermissionService with entityType='Namespace' and the given action" {
            val id1 = randomUUID()
            val id2 = randomUUID()
            every {
                permissionService.listEntitiesForUser(userId, EntityType.NAMESPACE, Action.READ)
            } returns listOf(id1.toString(), id2.toString())

            val result = service.findIdsVisibleTo(userId, Action.READ)

            result shouldContainExactly listOf(id1, id2)
        }

        "findIdsVisibleTo passes Action.WRITE through to the permission lookup" {
            val id1 = randomUUID()
            every {
                permissionService.listEntitiesForUser(userId, EntityType.NAMESPACE, Action.WRITE)
            } returns listOf(id1.toString())

            service.findIdsVisibleTo(userId, Action.WRITE) shouldContainExactly listOf(id1)
        }

        "findIdsVisibleTo returns empty list when permission lookup yields no entities" {
            every {
                permissionService.listEntitiesForUser(userId, EntityType.NAMESPACE, Action.READ)
            } returns emptyList()

            service.findIdsVisibleTo(userId, Action.READ) shouldBe emptyList()
        }

        // -------------------------------------------------------------------------
        // deployAgents
        // -------------------------------------------------------------------------

        "deployAgents throws ResourceNotFoundException when namespace does not exist" {
            val namespaceId = randomUUID()
            every { namespaceRepository.findByIds(listOf(namespaceId)) } returns emptyList()

            shouldThrow<ResourceNotFoundException> {
                service.deployAgents(namespaceId, listOf(randomUUID()))
            }
        }

        "deployAgents is a no-op when agent list is empty" {
            val namespaceId = randomUUID()

            service.deployAgents(namespaceId, emptyList())

            verify(exactly = 0) { namespaceRepository.findByIds(any()) }
            verify(exactly = 0) { namespaceRepository.deployAgents(any(), any()) }
        }

        "deployAgents delegates to repository when all agents belong to the namespace" {
            val namespaceId = randomUUID()
            val agentId1 = randomUUID()
            val agentId2 = randomUUID()
            every { namespaceRepository.findByIds(listOf(namespaceId)) } returns
                listOf(
                    Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns"),
                )
            every { agentConfigRepository.findByIds(listOf(agentId1, agentId2)) } returns
                listOf(
                    AgentConfig(metadata = EntityMetadata(id = agentId1), namespaceId = namespaceId, name = "Agent1"),
                    AgentConfig(metadata = EntityMetadata(id = agentId2), namespaceId = namespaceId, name = "Agent2"),
                )
            justRun { namespaceRepository.deployAgents(namespaceId, listOf(agentId1, agentId2)) }

            service.deployAgents(namespaceId, listOf(agentId1, agentId2))

            verify(exactly = 1) { namespaceRepository.deployAgents(namespaceId, listOf(agentId1, agentId2)) }
        }

        "deployAgents throws UnprocessableEntityException when an agent belongs to a different namespace" {
            val namespaceId = randomUUID()
            val otherNamespaceId = randomUUID()
            val agentId = randomUUID()
            every { namespaceRepository.findByIds(listOf(namespaceId)) } returns
                listOf(
                    Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns"),
                )
            every { agentConfigRepository.findByIds(listOf(agentId)) } returns
                listOf(
                    AgentConfig(metadata = EntityMetadata(id = agentId), namespaceId = otherNamespaceId, name = "Agent"),
                )

            shouldThrow<UnprocessableEntityException> {
                service.deployAgents(namespaceId, listOf(agentId))
            }
        }

        "deployAgents throws UnprocessableEntityException when an agent id is not found" {
            val namespaceId = randomUUID()
            val knownId = randomUUID()
            val unknownId = randomUUID()
            every { namespaceRepository.findByIds(listOf(namespaceId)) } returns
                listOf(
                    Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns"),
                )
            every { agentConfigRepository.findByIds(listOf(knownId, unknownId)) } returns
                listOf(
                    AgentConfig(metadata = EntityMetadata(id = knownId), namespaceId = namespaceId, name = "Agent"),
                )

            shouldThrow<UnprocessableEntityException> {
                service.deployAgents(namespaceId, listOf(knownId, unknownId))
            }
        }

        // -------------------------------------------------------------------------
        // undeployAgents
        // -------------------------------------------------------------------------

        "undeployAgents throws ResourceNotFoundException when namespace does not exist" {
            val namespaceId = randomUUID()
            every { namespaceRepository.findByIds(listOf(namespaceId)) } returns emptyList()

            shouldThrow<ResourceNotFoundException> {
                service.undeployAgents(namespaceId, listOf(randomUUID()))
            }
        }

        "undeployAgents is a no-op when agent list is empty" {
            val namespaceId = randomUUID()

            service.undeployAgents(namespaceId, emptyList())

            verify(exactly = 0) { namespaceRepository.findByIds(any()) }
            verify(exactly = 0) { namespaceRepository.undeployAgents(any(), any()) }
        }

        "undeployAgents delegates to repository when namespace exists" {
            val namespaceId = randomUUID()
            val agentId = randomUUID()
            every { namespaceRepository.findByIds(listOf(namespaceId)) } returns
                listOf(
                    Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns"),
                )
            justRun { namespaceRepository.undeployAgents(namespaceId, listOf(agentId)) }

            service.undeployAgents(namespaceId, listOf(agentId))

            verify(exactly = 1) { namespaceRepository.undeployAgents(namespaceId, listOf(agentId)) }
        }

        "findIdsVisibleTo drops malformed UUID strings defensively" {
            val valid = randomUUID()
            every {
                permissionService.listEntitiesForUser(userId, EntityType.NAMESPACE, Action.READ)
            } returns listOf(valid.toString(), "not-a-uuid", "")

            val result = service.findIdsVisibleTo(userId, Action.READ)

            result shouldHaveSize 1
            result.first() shouldBe valid
        }
    })
