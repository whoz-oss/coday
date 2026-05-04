package io.whozoss.agentos.usergroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.exception.ResourceNotFoundException
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.util.UUID

class UserGroupControllerSpec : StringSpec({
    timeout = 5000

    val userGroupService = mockk<UserGroupService>()
    val controller = UserGroupController(userGroupService)
    val namespaceId = UUID.randomUUID()

    fun userGroup(
        id: UUID = UUID.randomUUID(),
        nsId: UUID = namespaceId,
        name: String = "Group A",
    ) = UserGroup(
        metadata = EntityMetadata(id = id),
        namespaceId = nsId,
        name = name,
    )

    "list returns user groups for namespace" {
        val ug1 = userGroup(name = "Group A")
        val ug2 = userGroup(name = "Group B")
        val agent1 = UUID.randomUUID()
        every { userGroupService.list(namespaceId) } returns listOf(ug1, ug2)
        every { userGroupService.getAgentIds(ug1.id) } returns listOf(agent1)
        every { userGroupService.getAgentIds(ug2.id) } returns emptyList()
        every { userGroupService.countUsers(ug1.id) } returns 3
        every { userGroupService.countUsers(ug2.id) } returns 1

        val result = controller.list(UserGroupListRequest(namespaceId))

        result.data.size shouldBe 2
        result.data[0].name shouldBe "Group A"
        result.data[0].agentIds shouldBe listOf(agent1)
        result.data[0].userCount shouldBe 3
        result.data[1].name shouldBe "Group B"
        result.data[1].agentIds shouldBe emptyList()
    }

    "list returns empty data when no groups exist" {
        every { userGroupService.list(namespaceId) } returns emptyList()

        val result = controller.list(UserGroupListRequest(namespaceId))

        result.data shouldBe emptyList()
    }

    "get returns user group response" {
        val ug = userGroup()
        val agentId = UUID.randomUUID()
        every { userGroupService.get(ug.id) } returns ug
        every { userGroupService.getAgentIds(ug.id) } returns listOf(agentId)
        every { userGroupService.countUsers(ug.id) } returns 5

        val result = controller.get(ug.id, namespaceId)

        result.userGroupId shouldBe ug.id
        result.namespaceId shouldBe namespaceId
        result.name shouldBe "Group A"
        result.agentIds shouldBe listOf(agentId)
        result.userCount shouldBe 5
    }

    "get throws ResourceNotFoundException when not found" {
        val id = UUID.randomUUID()
        every { userGroupService.get(id) } throws ResourceNotFoundException("UserGroup not found: $id")

        val ex = runCatching { controller.get(id, namespaceId) }.exceptionOrNull()

        (ex is ResourceNotFoundException) shouldBe true
    }

    "create delegates to service and returns response" {
        val request = UserGroupCreateRequest(
            namespaceId = namespaceId,
            name = "New Group",
            userIds = setOf(UUID.randomUUID()),
            agentIds = setOf(UUID.randomUUID()),
        )
        val created = userGroup(name = "New Group")
        val agentId = request.agentIds.first()
        every { userGroupService.create(request) } returns created
        every { userGroupService.getAgentIds(created.id) } returns listOf(agentId)
        every { userGroupService.countUsers(created.id) } returns 1

        val result = controller.create(request)

        result.userGroupId shouldBe created.id
        result.name shouldBe "New Group"
        result.userCount shouldBe 1
        result.agentIds shouldBe listOf(agentId)
        verify(exactly = 1) { userGroupService.create(request) }
    }

    "update delegates to service and returns response" {
        val ugId = UUID.randomUUID()
        val request = UserGroupUpdateRequest(
            namespaceId = namespaceId,
            name = "Updated Group",
            addUserIds = setOf(UUID.randomUUID()),
            removeUserIds = emptySet(),
            agentIds = setOf(UUID.randomUUID()),
        )
        val updated = userGroup(id = ugId, name = "Updated Group")
        every { userGroupService.update(ugId, request) } returns updated
        every { userGroupService.getAgentIds(ugId) } returns request.agentIds.toList()
        every { userGroupService.countUsers(ugId) } returns 2

        val result = controller.update(ugId, request)

        result.userGroupId shouldBe ugId
        result.name shouldBe "Updated Group"
        result.userCount shouldBe 2
        verify(exactly = 1) { userGroupService.update(ugId, request) }
    }

    "delete delegates to service" {
        val ugId = UUID.randomUUID()
        justRun { userGroupService.delete(ugId) }

        controller.delete(ugId, namespaceId)

        verify(exactly = 1) { userGroupService.delete(ugId) }
    }
})
