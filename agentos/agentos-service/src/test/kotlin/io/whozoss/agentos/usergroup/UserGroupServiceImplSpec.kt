package io.whozoss.agentos.usergroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import java.util.UUID

class UserGroupServiceImplSpec :
    StringSpec({
        timeout = 5000

        val namespaceId = UUID.randomUUID()
        val defaultConfig = UserGroupConfigProperties()

        fun makeGroup(
            id: UUID = UUID.randomUUID(),
            name: String = "Group A",
        ) = UserGroup(
            metadata = EntityMetadata(id = id),
            namespaceId = namespaceId,
            name = name,
        )

        "list delegates to repository findByParent" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val groups = listOf(makeGroup(name = "A"), makeGroup(name = "B"))
            every { repo.findByParent(namespaceId) } returns groups

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)

            service.list(namespaceId) shouldBe groups
        }

        "get returns user group when found" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val group = makeGroup()
            every { repo.findByIds(listOf(group.id)) } returns listOf(group)

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)

            service.get(group.id) shouldBe group
        }

        "get throws ResourceNotFoundException when not found" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val id = UUID.randomUUID()
            every { repo.findByIds(listOf(id)) } returns emptyList()

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)

            val ex = runCatching { service.get(id) }.exceptionOrNull()
            (ex is ResourceNotFoundException) shouldBe true
        }

        "getAgentIds delegates to repository" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val groupId = UUID.randomUUID()
            val agentIds = listOf(UUID.randomUUID(), UUID.randomUUID())
            every { repo.findAgentIds(groupId) } returns agentIds

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)

            service.getAgentIds(groupId) shouldBe agentIds
        }

        "countUsers delegates to repository" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val groupId = UUID.randomUUID()
            every { repo.countUsers(groupId) } returns 42

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)

            service.countUsers(groupId) shouldBe 42
        }

        "create saves user group and adds users" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val userId1 = UUID.randomUUID()
            val userId2 = UUID.randomUUID()
            val request =
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "New Group",
                    userIds = setOf(userId1, userId2),
                    agentIds = emptySet(),
                )
            val savedSlot = slot<UserGroup>()
            every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }
            justRun { repo.addUser(any(), any()) }

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)
            val result = service.create(request)

            result.name shouldBe "New Group"
            verify(exactly = 1) { repo.addUser(any(), userId1) }
            verify(exactly = 1) { repo.addUser(any(), userId2) }
        }

        "create adds agents when provided" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val agentId = UUID.randomUUID()
            val request =
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "With Agents",
                    userIds = emptySet(),
                    agentIds = setOf(agentId),
                )
            val savedSlot = slot<UserGroup>()
            every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }
            justRun { repo.replaceAgents(any(), any()) }

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)
            service.create(request)

            verify(exactly = 1) { repo.replaceAgents(any(), setOf(agentId)) }
        }

        "create with no users or agents only saves the group" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val request =
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "Empty Group",
                )
            val savedSlot = slot<UserGroup>()
            every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)
            val result = service.create(request)

            result.name shouldBe "Empty Group"
            verify(exactly = 0) { repo.addUser(any(), any()) }
            verify(exactly = 0) { repo.replaceAgents(any(), any()) }
        }

        "update applies addUserIds and removeUserIds" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val groupId = UUID.randomUUID()
            val existing = makeGroup(id = groupId)
            val addedUser = UUID.randomUUID()
            val removedUser = UUID.randomUUID()
            val request =
                UserGroupUpdateRequest(
                    namespaceId = namespaceId,
                    name = "Updated",
                    addUserIds = setOf(addedUser),
                    removeUserIds = setOf(removedUser),
                    agentIds = emptySet(),
                )
            every { repo.findByIds(listOf(groupId)) } returns listOf(existing)
            every { repo.countUsers(groupId) } returns 3
            justRun { repo.addUser(groupId, addedUser) }
            justRun { repo.removeUser(groupId, removedUser) }
            justRun { repo.replaceAgents(groupId, emptySet()) }
            val savedSlot = slot<UserGroup>()
            every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)
            val result = service.update(groupId, request)

            result.name shouldBe "Updated"
            verify(exactly = 1) { repo.addUser(groupId, addedUser) }
            verify(exactly = 1) { repo.removeUser(groupId, removedUser) }
        }

        "update replaces agents" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val groupId = UUID.randomUUID()
            val existing = makeGroup(id = groupId)
            val newAgentId = UUID.randomUUID()
            val request =
                UserGroupUpdateRequest(
                    namespaceId = namespaceId,
                    name = "Group A",
                    agentIds = setOf(newAgentId),
                )
            every { repo.findByIds(listOf(groupId)) } returns listOf(existing)
            every { repo.countUsers(groupId) } returns 0
            justRun { repo.replaceAgents(groupId, setOf(newAgentId)) }

            val savedSlot = slot<UserGroup>()
            every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)
            service.update(groupId, request)

            verify(exactly = 1) { repo.replaceAgents(groupId, setOf(newAgentId)) }
        }

        "delete delegates to softDeleteWithRelationships" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val groupId = UUID.randomUUID()
            justRun { repo.softDeleteWithRelationships(groupId) }

            val service = UserGroupServiceImpl(repo, userServiceMock, defaultConfig)
            service.delete(groupId)

            verify(exactly = 1) { repo.softDeleteWithRelationships(groupId) }
        }

        "create throws when userIds exceed max" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val config = UserGroupConfigProperties(maxUsersPerGroup = 2, maxAgentsPerGroup = 1000)
            val request =
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "Big Group",
                    userIds = setOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
                )

            val service = UserGroupServiceImpl(repo, userServiceMock, config)

            val ex = runCatching { service.create(request) }.exceptionOrNull()
            (ex is UserGroupLimitExceededException) shouldBe true
        }

        "create throws when agentIds exceed max" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val config = UserGroupConfigProperties(maxUsersPerGroup = 30000, maxAgentsPerGroup = 1)
            val request =
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "Agent Heavy",
                    agentIds = setOf(UUID.randomUUID(), UUID.randomUUID()),
                )

            val service = UserGroupServiceImpl(repo, userServiceMock, config)

            val ex = runCatching { service.create(request) }.exceptionOrNull()
            (ex is UserGroupLimitExceededException) shouldBe true
        }

        "update throws when resulting userCount exceeds max" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val config = UserGroupConfigProperties(maxUsersPerGroup = 3, maxAgentsPerGroup = 1000)
            val groupId = UUID.randomUUID()
            val existing = makeGroup(id = groupId)
            val request =
                UserGroupUpdateRequest(
                    namespaceId = namespaceId,
                    name = "Group A",
                    addUserIds = setOf(UUID.randomUUID()),
                    agentIds = emptySet(),
                )
            every { repo.findByIds(listOf(groupId)) } returns listOf(existing)
            every { repo.countUsers(groupId) } returns 3

            val service = UserGroupServiceImpl(repo, userServiceMock, config)

            val ex = runCatching { service.update(groupId, request) }.exceptionOrNull()
            (ex is UserGroupLimitExceededException) shouldBe true
        }

        "update resolves removeUserExternalIds" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val config = UserGroupConfigProperties()
            val groupId = UUID.randomUUID()
            val existing = makeGroup(id = groupId)
            val resolvedUserId = UUID.randomUUID()
            val request =
                UserGroupUpdateRequest(
                    namespaceId = namespaceId,
                    name = "Group A",
                    removeUserExternalIds = setOf("ext-remove"),
                    agentIds = emptySet(),
                )
            every { repo.findByIds(listOf(groupId)) } returns listOf(existing)
            every { repo.countUsers(groupId) } returns 1
            every { userServiceMock.findByExternalId("ext-remove") } returns
                User(
                    metadata = EntityMetadata(id = resolvedUserId),
                    externalId = "ext-remove",
                )
            justRun { repo.removeUser(groupId, resolvedUserId) }
            justRun { repo.replaceAgents(groupId, emptySet()) }
            val savedSlot = slot<UserGroup>()
            every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }

            val service = UserGroupServiceImpl(repo, userServiceMock, config)
            service.update(groupId, request)

            verify(exactly = 1) { userServiceMock.findByExternalId("ext-remove") }
            verify(exactly = 1) { repo.removeUser(groupId, resolvedUserId) }
        }

        "update skips removeUserExternalIds that do not resolve" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val config = UserGroupConfigProperties()
            val groupId = UUID.randomUUID()
            val existing = makeGroup(id = groupId)
            val request =
                UserGroupUpdateRequest(
                    namespaceId = namespaceId,
                    name = "Group A",
                    removeUserExternalIds = setOf("unknown-ext"),
                    agentIds = emptySet(),
                )
            every { repo.findByIds(listOf(groupId)) } returns listOf(existing)
            every { repo.countUsers(groupId) } returns 0
            every { userServiceMock.findByExternalId("unknown-ext") } returns null
            justRun { repo.replaceAgents(groupId, emptySet()) }
            val savedSlot = slot<UserGroup>()
            every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }

            val service = UserGroupServiceImpl(repo, userServiceMock, config)
            service.update(groupId, request)

            verify(exactly = 0) { repo.removeUser(any(), any()) }
        }

        "update throws when agentIds exceed max" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val config = UserGroupConfigProperties(maxUsersPerGroup = 30000, maxAgentsPerGroup = 1)
            val groupId = UUID.randomUUID()
            val existing = makeGroup(id = groupId)
            val request =
                UserGroupUpdateRequest(
                    namespaceId = namespaceId,
                    name = "Group A",
                    agentIds = setOf(UUID.randomUUID(), UUID.randomUUID()),
                )
            every { repo.findByIds(listOf(groupId)) } returns listOf(existing)
            every { repo.countUsers(groupId) } returns 0

            val service = UserGroupServiceImpl(repo, userServiceMock, config)

            val ex = runCatching { service.update(groupId, request) }.exceptionOrNull()
            (ex is UserGroupLimitExceededException) shouldBe true
        }

        "create resolves userExternalIds and adds them to the group" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val config = UserGroupConfigProperties()
            val resolvedUserId = UUID.randomUUID()
            val request =
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "External Group",
                    userExternalIds = setOf("ext-user-1"),
                )
            every { userServiceMock.resolveOrCreateByExternalId("ext-user-1") } returns
                User(
                    metadata = EntityMetadata(id = resolvedUserId),
                    externalId = "ext-user-1",
                )
            val savedSlot = slot<UserGroup>()
            every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }
            justRun { repo.addUser(any(), resolvedUserId) }

            val service = UserGroupServiceImpl(repo, userServiceMock, config)
            service.create(request)

            verify(exactly = 1) { userServiceMock.resolveOrCreateByExternalId("ext-user-1") }
            verify(exactly = 1) { repo.addUser(any(), resolvedUserId) }
        }

        "create combines userIds and userExternalIds for limit check" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val config = UserGroupConfigProperties(maxUsersPerGroup = 2, maxAgentsPerGroup = 1000)
            val resolvedUserId = UUID.randomUUID()
            val request =
                UserGroupCreateRequest(
                    namespaceId = namespaceId,
                    name = "Over Limit",
                    userIds = setOf(UUID.randomUUID(), UUID.randomUUID()),
                    userExternalIds = setOf("ext-1"),
                )
            every { userServiceMock.resolveOrCreateByExternalId("ext-1") } returns
                User(
                    metadata = EntityMetadata(id = resolvedUserId),
                    externalId = "ext-1",
                )

            val service = UserGroupServiceImpl(repo, userServiceMock, config)

            val ex = runCatching { service.create(request) }.exceptionOrNull()
            (ex is UserGroupLimitExceededException) shouldBe true
        }

        "update resolves addUserExternalIds" {
            val repo = mockk<UserGroupRepository>()
            val userServiceMock = mockk<UserService>()
            val config = UserGroupConfigProperties()
            val groupId = UUID.randomUUID()
            val existing = makeGroup(id = groupId)
            val resolvedUserId = UUID.randomUUID()
            val request =
                UserGroupUpdateRequest(
                    namespaceId = namespaceId,
                    name = "Group A",
                    addUserExternalIds = setOf("ext-new"),
                    agentIds = emptySet(),
                )
            every { repo.findByIds(listOf(groupId)) } returns listOf(existing)
            every { repo.countUsers(groupId) } returns 0
            every { userServiceMock.resolveOrCreateByExternalId("ext-new") } returns
                User(
                    metadata = EntityMetadata(id = resolvedUserId),
                    externalId = "ext-new",
                )
            justRun { repo.addUser(groupId, resolvedUserId) }
            justRun { repo.replaceAgents(groupId, emptySet()) }
            val savedSlot = slot<UserGroup>()
            every { repo.save(capture(savedSlot)) } answers { savedSlot.captured }

            val service = UserGroupServiceImpl(repo, userServiceMock, config)
            service.update(groupId, request)

            verify(exactly = 1) { userServiceMock.resolveOrCreateByExternalId("ext-new") }
            verify(exactly = 1) { repo.addUser(groupId, resolvedUserId) }
        }
    })
