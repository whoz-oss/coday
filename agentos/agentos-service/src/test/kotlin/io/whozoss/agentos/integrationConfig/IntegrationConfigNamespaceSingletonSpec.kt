package io.whozoss.agentos.integrationConfig

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Rules applying to an integration type that configures the namespace itself rather than a tool
 * (see [IntegrationTypeConstraints]): namespace-shared scope only, one active row per namespace.
 *
 * The service-level checks tested here produce the actionable message. The guarantee under
 * concurrency is the `integration_config_singleton_key_unique` database constraint, which only a
 * persistence spec can exercise — this in-memory fixture has no constraint.
 */
class IntegrationConfigNamespaceSingletonSpec :
    StringSpec({

        val singletonType = IntegrationTypeConstraints.GIT_REPOSITORY_TYPE

        fun newService(): IntegrationConfigServiceImpl =
            IntegrationConfigServiceImpl(InMemoryIntegrationConfigRepository(), IntegrationConfigMergeStrategy())

        fun config(
            namespaceId: UUID?,
            userId: UUID? = null,
            name: String = "project-repository",
            integrationType: String = singletonType,
        ): IntegrationConfig =
            IntegrationConfig(
                metadata = EntityMetadata(),
                namespaceId = namespaceId,
                userId = userId,
                name = name,
                integrationType = integrationType,
            )

        "a namespace-shared association is created" {
            val service = newService()
            val namespaceId = UUID.randomUUID()

            val saved = service.create(config(namespaceId))

            saved.namespaceId shouldBe namespaceId
            service.findActiveNamespaceSingleton(namespaceId, singletonType).shouldNotBeNull()
        }

        "a second association in the same namespace is refused" {
            val service = newService()
            val namespaceId = UUID.randomUUID()
            service.create(config(namespaceId, name = "project-repository"))

            val error = shouldThrow<ResponseStatusException> { service.create(config(namespaceId, name = "another-repository")) }

            error.statusCode shouldBe HttpStatus.CONFLICT
        }

        "a user-scoped association is refused: it would redirect provisioning for that member" {
            val service = newService()

            val error =
                shouldThrow<ResponseStatusException> {
                    service.create(config(UUID.randomUUID(), userId = UUID.randomUUID()))
                }

            error.statusCode shouldBe HttpStatus.UNPROCESSABLE_ENTITY
        }

        "a platform-scoped association is refused" {
            val service = newService()

            val error = shouldThrow<ResponseStatusException> { service.create(config(namespaceId = null)) }

            error.statusCode shouldBe HttpStatus.UNPROCESSABLE_ENTITY
        }

        "each namespace may have its own association" {
            val service = newService()
            val first = UUID.randomUUID()
            val second = UUID.randomUUID()

            service.create(config(first))
            service.create(config(second))

            service.findActiveNamespaceSingleton(first, singletonType).shouldNotBeNull()
            service.findActiveNamespaceSingleton(second, singletonType).shouldNotBeNull()
        }

        "an ordinary integration type is still allowed several rows per namespace" {
            val service = newService()
            val namespaceId = UUID.randomUUID()

            service.create(config(namespaceId, name = "JIRA_PROD", integrationType = "JIRA"))
            service.create(config(namespaceId, name = "JIRA_STAGING", integrationType = "JIRA"))

            service.findByNamespaceShared(namespaceId).size shouldBe 2
        }

        "an ordinary integration type is still allowed at user scope" {
            val service = newService()

            service.create(config(UUID.randomUUID(), userId = UUID.randomUUID(), name = "JIRA", integrationType = "JIRA"))
        }

        "removing the association frees the slot" {
            val service = newService()
            val namespaceId = UUID.randomUUID()
            val existing = service.create(config(namespaceId))

            service.delete(existing.id)

            service.findActiveNamespaceSingleton(namespaceId, singletonType).shouldBeNull()
            service.create(config(namespaceId, name = "replacement-repository"))
        }

        "updating the association in place is not a conflict with itself" {
            val service = newService()
            val namespaceId = UUID.randomUUID()
            val existing = service.create(config(namespaceId))

            service.update(existing.copy(description = "now documented"))
        }

        "the singleton discriminator is only written for a row the rule applies to" {
            val namespaceId = UUID.randomUUID()

            IntegrationConfigNode
                .computeSingletonKey(config(namespaceId))
                .shouldNotBeNull()

            // Wrong scope, non-singleton type, or soft-deleted: no value, so the row does not
            // compete for the unique slot. Absence is the exemption mechanism in Neo4j.
            IntegrationConfigNode.computeSingletonKey(config(namespaceId, userId = UUID.randomUUID())).shouldBeNull()
            IntegrationConfigNode.computeSingletonKey(config(namespaceId = null)).shouldBeNull()
            IntegrationConfigNode.computeSingletonKey(config(namespaceId, integrationType = "JIRA")).shouldBeNull()
            IntegrationConfigNode
                .computeSingletonKey(
                    config(namespaceId).let { it.copy(metadata = it.metadata.copy(removed = true)) },
                ).shouldBeNull()
        }
    })
