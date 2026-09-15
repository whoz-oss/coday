package io.whozoss.agentos.permissions

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

class Neo4jPermissionRepositorySpec :
    StringSpec({

        val permissionNodeRepository = mockk<PermissionNodeNeo4jRepository>()
        val repository = Neo4jPermissionRepository(permissionNodeRepository)

        "listRelationsForUsers rethrows a failed lookup instead of reporting no relation" {
            val entityId = UUID.randomUUID().toString()
            val userIds = listOf(UUID.randomUUID().toString())
            every {
                permissionNodeRepository.findRelationsForUsers(userIds, entityId, EntityType.NAMESPACE.label)
            } throws IllegalStateException("Cypher failure")

            shouldThrow<IllegalStateException> {
                repository.listRelationsForUsers(EntityType.NAMESPACE, entityId, userIds)
            }.message shouldBe "Cypher failure"
        }
    })
