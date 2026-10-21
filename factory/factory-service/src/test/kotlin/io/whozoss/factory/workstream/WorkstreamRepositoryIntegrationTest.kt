package io.whozoss.factory.workstream

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.error.BadRequestException
import io.whozoss.factory.error.ConflictException
import io.whozoss.factory.error.ResourceNotFoundException
import io.whozoss.factory.error.RevisionConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workstream.domain.Workstream
import io.whozoss.factory.workstream.domain.WorkstreamStatus
import io.whozoss.factory.workstream.persistence.Neo4jWorkstreamRepository
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
import io.whozoss.factory.workstream.web.UpdateWorkstreamRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Integration tests of the tenant-scoped Neo4j repository backing the
 * `/api/factory/workstreams` surface.
 *
 * Runs against the in-process Neo4j harness via [Neo4jDomainIntegrationTest];
 * the graph is cleared before each test by the shared fixture.
 */
class WorkstreamRepositoryIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var repository: Neo4jWorkstreamRepository

    @Autowired
    private lateinit var service: WorkstreamService

    private fun request(slug: String, name: String) = CreateWorkstreamRequest(
        slug = slug, name = name, status = "active", namespaceId = UUID.randomUUID().toString(),
    )

    @Test
    fun `create then read a workstream within the tenant scope`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        val created = service.create(scope, request(slug, "My Workstream"))
        assertThat(created["slug"]).isEqualTo(slug)
        assertThat(created["name"]).isEqualTo("My Workstream")
        assertThat(created["status"]).isEqualTo("active")

        val found = repository.findById(scope, slug)
        assertThat(found).isNotNull
        assertThat(found!!["slug"]).isEqualTo(slug)
        assertThat(found["name"]).isEqualTo("My Workstream")
        assertThat(found["status"]).isEqualTo("active")

        assertThat(repository.listByOrganization(scope.organizationId).map { it["slug"] }).contains(slug)
    }

    @Test
    fun `reads are constrained by the tenant scope`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        service.create(scope, request(slug, "Scoped"))

        val otherOrg = repository.findById(TenantScope("other-org", WORKSTREAM_ID), slug)
        assertThat(otherOrg).isNull()

        assertThat(repository.listByOrganization("other-org")).isEmpty()
    }

    @Test
    fun `delete removes a workstream only within its tenant scope`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        service.create(scope, request(slug, "Doomed"))

        assertThat(repository.deleteById(TenantScope("other-org", WORKSTREAM_ID), slug)).isFalse()
        assertThat(repository.findById(scope, slug)).isNotNull
        assertThat(repository.deleteById(scope, slug)).isTrue()
        assertThat(repository.findById(scope, slug)).isNull()
    }

    @Test
    fun `the service rejects a duplicate slug`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        service.create(scope, request(slug, "First"))

        assertThatThrownBy { service.create(scope, request(slug, "Second")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `the service rejects an invalid slug`() {
        assertThatThrownBy { service.create(scope, "Not_A_Slug", "Name", "active") }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `enriched create persists and reads back the versioned registry fields`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        val created = service.create(
            scope,
            CreateWorkstreamRequest(
                slug = slug,
                name = "Enriched",
                status = "paused",
                namespaceId = "82a43806-3a78-4e56-96c4-cf3039989452",
                controllerAgentRef = "agent://controller",
                allowedWorkflowTypes = listOf("wf-allowed", "wf-other"),
                governancePolicyRef = "policy://gov",
            ),
        )
        assertThat(created["slug"]).isEqualTo(slug)
        assertThat(created["workstreamId"]).isEqualTo(slug)
        assertThat(created["title"]).isEqualTo("Enriched")
        assertThat(created["status"]).isEqualTo("paused")
        assertThat(created["namespaceId"]).isEqualTo("82a43806-3a78-4e56-96c4-cf3039989452")
        assertThat(created["controllerAgentRef"]).isEqualTo("agent://controller")
        assertThat(created["allowedWorkflowTypes"]).isEqualTo(listOf("wf-allowed", "wf-other"))
        assertThat(created["governancePolicyRef"]).isEqualTo("policy://gov")
        assertThat(created["revision"]).isEqualTo(1)
        assertThat(created["createdAt"]).isNotNull()
        assertThat(created["updatedAt"]).isNotNull()

        val domain = repository.findDomain(scope, slug)
        assertThat(domain).isNotNull
        assertThat(domain!!.status).isEqualTo(WorkstreamStatus.PAUSED)
        assertThat(domain.slug).isEqualTo(slug)
        assertThat(domain.title).isEqualTo("Enriched")
        assertThat(domain.allowedWorkflowTypes).containsExactly("wf-allowed", "wf-other")
    }

    @Test
    fun `save bumps the revision and updatedAt while preserving createdAt`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        val created = repository.create(
            scope,
            Workstream(
                organizationId = scope.organizationId,
                workstreamId = slug,
                name = "Versioned",
                namespaceId = UUID.randomUUID().toString(),
                status = WorkstreamStatus.ACTIVE,
            ),
        )
        assertThat(created.revision).isEqualTo(1)

        val saved = repository.save(scope, created.copy(name = "Versioned v2", status = WorkstreamStatus.ARCHIVED))
        assertThat(saved.revision).isEqualTo(2)
        assertThat(saved.name).isEqualTo("Versioned v2")
        assertThat(saved.status).isEqualTo(WorkstreamStatus.ARCHIVED)
        assertThat(saved.createdAt).isEqualTo(created.createdAt)
        assertThat(saved.updatedAt).isAfterOrEqualTo(created.updatedAt)
    }

    @Test
    fun `save of an absent workstream is a NOT_FOUND`() {
        val missing = Workstream(
            organizationId = scope.organizationId,
            workstreamId = "ws-absent",
            name = "Absent",
            status = WorkstreamStatus.ACTIVE,
        )
        assertThatThrownBy { repository.save(scope, missing) }
            .isInstanceOf(ResourceNotFoundException::class.java)
    }

    @Test
    fun `update applies changes and bumps the revision`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        service.create(scope, request(slug, "Updatable"))

        val updated = service.update(
            scope,
            slug,
            UpdateWorkstreamRequest(status = "paused", allowedWorkflowTypes = listOf("wf-a")),
            expectedRevision = 1,
        )
        assertThat(updated["revision"]).isEqualTo(2)
        assertThat(updated["status"]).isEqualTo("paused")
        assertThat(updated["allowedWorkflowTypes"]).isEqualTo(listOf("wf-a"))
        assertThat(updated["name"]).isEqualTo("Updatable")
    }

    @Test
    fun `update with a stale expected revision is a REVISION_CONFLICT`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        service.create(scope, request(slug, "Conflicted"))
        service.update(scope, slug, UpdateWorkstreamRequest(name = "Second"), expectedRevision = 1)

        assertThatThrownBy {
            service.update(scope, slug, UpdateWorkstreamRequest(name = "Third"), expectedRevision = 1)
        }.isInstanceOf(RevisionConflictException::class.java)
    }

    @Test
    fun `the service rejects an unknown status`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        assertThatThrownBy { service.create(scope, slug, "Bad status", "doomed") }
            .isInstanceOf(BadRequestException::class.java)

        service.create(scope, request(slug, "Good status"))
        assertThatThrownBy { service.update(scope, slug, UpdateWorkstreamRequest(status = "doomed")) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `create rejects a namespace already assigned in the same organization`() {
        val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"
        service.create(scope, CreateWorkstreamRequest(slug = "ws-first", name = "First", status = "active", namespaceId = namespace))

        assertThatThrownBy {
            service.create(scope, CreateWorkstreamRequest(slug = "ws-second", name = "Second", status = "active", namespaceId = namespace.uppercase()))
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `update rejects reassignment of a namespace already owned by another workstream`() {
        val namespace = "1e5ce582-ea48-49e9-b9f8-d0a0b6f82e28"
        service.create(scope, CreateWorkstreamRequest(slug = "ws-owner", name = "Owner", status = "active", namespaceId = namespace))
        service.create(scope, request("ws-target", "Target"))

        assertThatThrownBy {
            service.update(scope, "ws-target", UpdateWorkstreamRequest(namespaceId = namespace), expectedRevision = 1)
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `namespace uniqueness is isolated by organization`() {
        val namespace = "2e5ce582-ea48-49e9-b9f8-d0a0b6f82e28"
        val otherScope = TenantScope("other-org", "ws-other")
        service.create(scope, CreateWorkstreamRequest(slug = "ws-local", name = "Local", status = "active", namespaceId = namespace))
        service.create(otherScope, CreateWorkstreamRequest(slug = "ws-foreign", name = "Foreign", status = "active", namespaceId = namespace))

        assertThat(service.findByNamespace(scope.organizationId, namespace)?.workstreamId).isEqualTo("ws-local")
        assertThat(service.findByNamespace(otherScope.organizationId, namespace)?.workstreamId).isEqualTo("ws-foreign")
    }

    @Test
    fun `read only namespace resolution never initializes a workstream`() {
        val namespace = "3e5ce582-ea48-49e9-b9f8-d0a0b6f82e28"

        assertThat(service.findByNamespace(scope.organizationId, namespace)).isNull()
        assertThat(repository.findById(scope, "namespace-$namespace")).isNull()
    }

    @Test
    fun `concurrent namespace initialization converges on one workstream`() {
        val namespace = "4e5ce582-ea48-49e9-b9f8-d0a0b6f82e28"
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit<String> {
                    ready.countDown()
                    start.await()
                    service.findOrInitializeForNamespace(scope.organizationId, namespace).workstreamId
                }
            }
            ready.await()
            start.countDown()

            assertThat(futures.map { it.get() }).containsOnly("namespace-$namespace")
            assertThat(repository.findDomainsByNamespace(scope.organizationId, namespace)).hasSize(1)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `direct repository writes reject missing or malformed namespaces without persisting`() {
        listOf(null, "", "ns-invalid").forEach { invalid ->
            val workstream = Workstream(scope.organizationId, "invalid", invalid, "Invalid", WorkstreamStatus.ACTIVE)
            assertThatThrownBy { repository.create(scope, workstream) }.isInstanceOf(BadRequestException::class.java)
            assertThat(repository.findDomain(scope, "invalid")).isNull()
            assertThatThrownBy {
                repository.mergeInitialNamespaceMapping(scope.organizationId, "invalid", invalid.orEmpty(), "Invalid")
            }.isInstanceOf(BadRequestException::class.java)
        }
    }

    @Test
    fun `direct repository save validates and normalizes namespace before writing`() {
        val namespace = "ae3c175a-24b1-4bd6-946f-f533ef901fac"
        val created = repository.create(scope, Workstream(
            scope.organizationId, "direct", " ${namespace.uppercase()} ", "Direct", WorkstreamStatus.ACTIVE,
        ))
        assertThat(created.namespaceId).isEqualTo(namespace)
        listOf(null, "", "invalid").forEach { invalid ->
            assertThatThrownBy { repository.save(scope, created.copy(namespaceId = invalid)) }
                .isInstanceOf(BadRequestException::class.java)
            assertThat(repository.findDomain(scope, "direct")!!.namespaceId).isEqualTo(namespace)
        }
        val saved = repository.save(scope, created.copy(namespaceId = namespace.uppercase()))
        assertThat(saved.namespaceId).isEqualTo(namespace)
    }

    @Test
    fun `the service rejects invalid allowed workflow types`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        assertThatThrownBy {
            service.create(
                scope,
                CreateWorkstreamRequest(slug = slug, name = "Bad types", status = "active", allowedWorkflowTypes = listOf("Not A Type")),
            )
        }.isInstanceOf(BadRequestException::class.java)
    }
}
