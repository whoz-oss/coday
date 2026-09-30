package io.whozoss.factory.workstream

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.error.BadRequestException
import io.whozoss.factory.error.ConflictException
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workstream.persistence.Neo4jWorkstreamRepository
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
}
