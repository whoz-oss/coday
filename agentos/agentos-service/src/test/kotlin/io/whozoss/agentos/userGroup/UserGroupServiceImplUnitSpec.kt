package io.whozoss.agentos.userGroup

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.agentConfig.AgentConfig
import io.whozoss.agentos.agentConfig.AgentConfigRepository
import io.whozoss.agentos.exception.UnprocessableEntityException
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserRepository
import java.util.*
import java.util.UUID.randomUUID

class UserGroupServiceImplUnitSpec :
    StringSpec({

        val namespaceId = randomUUID()
        val externalId = "federation-test"

        val namespace =
            Namespace(
                metadata = EntityMetadata(id = namespaceId),
                name = "test-namespace",
                externalId = externalId,
            )

        fun agentConfig(
            id: UUID = randomUUID(),
            nsId: UUID = namespaceId,
        ) = AgentConfig(
            metadata = EntityMetadata(id = id),
            namespaceId = nsId,
            name = "agent-$id",
        )

        fun buildService(
            userGroupRepository: UserGroupRepository = mockk(relaxed = true),
            namespaceService: NamespaceService = mockk(),
            agentConfigRepository: AgentConfigRepository = mockk(),
            userRepository: UserRepository = mockk(relaxed = true),
        ) = UserGroupServiceImpl(userGroupRepository, namespaceService, agentConfigRepository, userRepository)

        // -------------------------------------------------------------------------
        // createFromRequest — agent validation
        // -------------------------------------------------------------------------

        "createFromRequest with valid agents calls addAgents" {
            val agentId1 = randomUUID()
            val agentId2 = randomUUID()
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team A")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team A",
                    agentIds = listOf(agentId1, agentId2),
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()
            val agentConfigRepository = mockk<AgentConfigRepository>()

            every { namespaceService.findByExternalId(externalId) } returns namespace
            every { agentConfigRepository.findByIds(listOf(agentId1, agentId2)) } returns
                listOf(agentConfig(agentId1), agentConfig(agentId2))
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByNamespaceExternalId(externalId) } returns listOf(searchResult)

            val service = buildService(userGroupRepository, namespaceService, agentConfigRepository)
            val result =
                service.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceExternalId = externalId,
                        name = "Team A",
                        agentIds = listOf(agentId1, agentId2),
                    ),
                )

            verify(exactly = 1) {
                userGroupRepository.addAgents(
                    groupId,
                    match { ids ->
                        ids.toSet() == setOf(agentId1, agentId2)
                    },
                )
            }
            result.agentIds shouldContainExactlyInAnyOrder listOf(agentId1, agentId2)
        }

        "createFromRequest with no agents skips addAgents" {
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team B")
            val searchResult =
                UserGroupSearchResult(
                    userGroupId = groupId,
                    namespaceId = namespaceId,
                    namespaceExternalId = externalId,
                    name = "Team B",
                )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()
            val agentConfigRepository = mockk<AgentConfigRepository>()

            every { namespaceService.findByExternalId(externalId) } returns namespace
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByNamespaceExternalId(externalId) } returns listOf(searchResult)

            val service = buildService(userGroupRepository, namespaceService, agentConfigRepository)
            service.createFromRequest(
                UserGroupCreateRequest(
                    namespaceExternalId = externalId,
                    name = "Team B",
                ),
            )

            verify(exactly = 0) { userGroupRepository.addAgents(any(), any()) }
        }

        "createFromRequest throws 422 when an agentId belongs to a different namespace" {
            val agentId = randomUUID()
            val otherNamespaceId = randomUUID()

            val namespaceService = mockk<NamespaceService>()
            val agentConfigRepository = mockk<AgentConfigRepository>()

            every { namespaceService.findByExternalId(externalId) } returns namespace
            every { agentConfigRepository.findByIds(listOf(agentId)) } returns
                listOf(agentConfig(agentId, nsId = otherNamespaceId))

            val service = buildService(namespaceService = namespaceService, agentConfigRepository = agentConfigRepository)

            shouldThrow<UnprocessableEntityException> {
                service.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceExternalId = externalId,
                        name = "Team C",
                        agentIds = listOf(agentId),
                    ),
                )
            }
        }

        "createFromRequest throws 422 when an agentId does not exist" {
            val agentId = randomUUID()

            val namespaceService = mockk<NamespaceService>()
            val agentConfigRepository = mockk<AgentConfigRepository>()

            every { namespaceService.findByExternalId(externalId) } returns namespace
            every { agentConfigRepository.findByIds(listOf(agentId)) } returns emptyList()

            val service = buildService(namespaceService = namespaceService, agentConfigRepository = agentConfigRepository)

            shouldThrow<UnprocessableEntityException> {
                service.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceExternalId = externalId,
                        name = "Team D",
                        agentIds = listOf(agentId),
                    ),
                )
            }
        }

        // -------------------------------------------------------------------------
        // createFromRequest — user linking
        // -------------------------------------------------------------------------

        "createFromRequest with userExternalIds calls addUsers" {
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team E")
            val searchResult = UserGroupSearchResult(
                userGroupId = groupId,
                namespaceId = namespaceId,
                namespaceExternalId = externalId,
                name = "Team E",
                userCount = 2,
            )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()

            every { namespaceService.findByExternalId(externalId) } returns namespace
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByNamespaceExternalId(externalId) } returns listOf(searchResult)

            val service = buildService(userGroupRepository = userGroupRepository, namespaceService = namespaceService)
            val result = service.createFromRequest(
                UserGroupCreateRequest(
                    namespaceExternalId = externalId,
                    name = "Team E",
                    userExternalIds = listOf("alice@example.com", "bob@example.com"),
                ),
            )

            verify(exactly = 1) {
                userGroupRepository.addUsers(
                    groupId,
                    match { ids -> ids.toSet() == setOf("alice@example.com", "bob@example.com") },
                )
            }
            result.userCount shouldBe 2
        }

        "createFromRequest with no userExternalIds skips addUsers" {
            val groupId = randomUUID()
            val group = UserGroup(metadata = EntityMetadata(id = groupId), namespaceId = namespaceId, name = "Team F")
            val searchResult = UserGroupSearchResult(
                userGroupId = groupId,
                namespaceId = namespaceId,
                namespaceExternalId = externalId,
                name = "Team F",
            )

            val userGroupRepository = mockk<UserGroupRepository>(relaxed = true)
            val namespaceService = mockk<NamespaceService>()

            every { namespaceService.findByExternalId(externalId) } returns namespace
            every { userGroupRepository.save(any()) } returns group
            every { userGroupRepository.findByNamespaceExternalId(externalId) } returns listOf(searchResult)

            val service = buildService(userGroupRepository = userGroupRepository, namespaceService = namespaceService)
            service.createFromRequest(
                UserGroupCreateRequest(
                    namespaceExternalId = externalId,
                    name = "Team F",
                ),
            )

            verify(exactly = 0) { userGroupRepository.addUsers(any(), any()) }
        }
    })
