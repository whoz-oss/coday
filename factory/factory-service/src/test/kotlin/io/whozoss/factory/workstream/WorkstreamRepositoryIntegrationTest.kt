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

    @Test
    fun `create then read a workstream within the tenant scope`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        val created = repository.create(scope, slug, "My Workstream", "active")
        assertThat(created["slug"]).isEqualTo(slug)
        assertThat(created["name"]).isEqualTo("My Workstream")
        assertThat(created["status"]).isEqualTo("active")

        val found = repository.findById(scope, slug)
        assertThat(found).isNotNull
        assertThat(found!!["slug"]).isEqualTo(slug)
        assertThat(found["name"]).isEqualTo("My Workstream")
        assertThat(found["status"]).isEqualTo("active")

        assertThat(repository.list(scope).map { it["slug"] }).contains(slug)
    }

    @Test
    fun `reads are constrained by the tenant scope`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        repository.create(scope, slug, "Scoped", "active")

        val otherOrg = repository.findById(TenantScope("other-org", WORKSTREAM_ID), slug)
        assertThat(otherOrg).isNull()

        assertThat(repository.list(TenantScope("other-org", WORKSTREAM_ID))).isEmpty()
    }

    @Test
    fun `delete removes a workstream only within its tenant scope`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        repository.create(scope, slug, "Doomed", "active")

        assertThat(repository.deleteById(TenantScope("other-org", WORKSTREAM_ID), slug)).isFalse()
        assertThat(repository.findById(scope, slug)).isNotNull
        assertThat(repository.deleteById(scope, slug)).isTrue()
        assertThat(repository.findById(scope, slug)).isNull()
    }

    @Test
    fun `the service rejects a duplicate slug`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        service.create(scope, slug, "First", "active")

        assertThatThrownBy { service.create(scope, slug, "Second", "active") }
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
                namespaceId = "ns-enriched",
                controllerAgentRef = "agent://controller",
                allowedWorkflowTypes = listOf("wf-allowed", "wf-other"),
                governancePolicyRef = "policy://gov",
            ),
        )
        assertThat(created["slug"]).isEqualTo(slug)
        assertThat(created["workstreamId"]).isEqualTo(slug)
        assertThat(created["title"]).isEqualTo("Enriched")
        assertThat(created["status"]).isEqualTo("paused")
        assertThat(created["namespaceId"]).isEqualTo("ns-enriched")
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
        service.create(scope, slug, "Updatable", "active")

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
        service.create(scope, slug, "Conflicted", "active")
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

        service.create(scope, slug, "Good status", "active")
        assertThatThrownBy { service.update(scope, slug, UpdateWorkstreamRequest(status = "doomed")) }
            .isInstanceOf(BadRequestException::class.java)
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
