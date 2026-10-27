package io.whozoss.factory.workstream

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** Namespace mappings are explicit, organization-bound and never synthesized by reads. */
class WorkstreamNamespaceResolutionTest : Neo4jDomainIntegrationTest() {
    @Autowired
    private lateinit var workstreamService: WorkstreamService

    private val namespace = "0d4bd471-df37-43d8-a8f7-c989f95e71d7"

    @Test
    fun `lookup normalizes an existing namespace without changing its owner`() {
        workstreamService.create(scope, CreateWorkstreamRequest(
            slug = scope.workstreamId, name = "Mapped workstream", status = "active", namespaceId = namespace,
        ))
        val mapped = workstreamService.findByNamespace(scope.organizationId, " ${namespace.uppercase()} ")
        assertThat(mapped?.workstreamId).isEqualTo(scope.workstreamId)
        assertThat(workstreamService.requireConfiguredNamespace(scope)).isEqualTo(namespace)
    }

    @Test
    fun `lookup without mapping never initializes one`() {
        assertThat(workstreamService.findByNamespace(scope.organizationId, namespace)).isNull()
        assertThat(workstreamService.listByOrganization(scope.organizationId)).isEmpty()
        assertThatThrownBy { workstreamService.requireConfiguredNamespace(scope) }.isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `a workstream cannot be created without a namespace`() {
        assertThatThrownBy {
            workstreamService.create(scope, CreateWorkstreamRequest(slug = "unbound", name = "Unbound", status = "active"))
        }.isInstanceOf(RuntimeException::class.java)
        assertThat(workstreamService.listByOrganization(scope.organizationId)).isEmpty()
    }

    @Test
    fun `lookup cannot cross the authenticated organization`() {
        workstreamService.create(scope, CreateWorkstreamRequest(
            slug = scope.workstreamId, name = "Mapped workstream", status = "active", namespaceId = namespace,
        ))
        assertThat(workstreamService.findByNamespace("other-organization", namespace)).isNull()
    }
}
