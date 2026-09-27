package io.whozoss.factory.forge

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.error.ConflictException
import io.whozoss.factory.forge.infrastructure.JdbcWorkstreamRepository
import io.whozoss.factory.forge.service.WorkstreamService
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Integration tests of the tenant-scoped JDBC repository over the pre-existing
 * `workstreams` table (created by V2, never recreated here).
 */
class WorkstreamJdbcRepositoryTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var repository: JdbcWorkstreamRepository

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
    fun `the service rejects a duplicate slug`() {
        val slug = "ws-${UUID.randomUUID().toString().take(8)}"
        service.create(scope, slug, "First", "active")

        assertThatThrownBy { service.create(scope, slug, "Second", "active") }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `the service rejects an invalid slug`() {
        assertThatThrownBy { service.create(scope, "Not_A_Slug", "Name", "active") }
            .isInstanceOf(io.whozoss.factory.error.BadRequestException::class.java)
    }
}
