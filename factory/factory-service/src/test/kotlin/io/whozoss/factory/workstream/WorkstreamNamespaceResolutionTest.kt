package io.whozoss.factory.workstream

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.persistence.TenantScope
import io.whozoss.factory.workstream.web.CreateWorkstreamRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Lot D — explicit workstream <-> namespace resolution.
 *
 * The mapping is centralized in [WorkstreamService.resolveNamespaceId]: an
 * explicit request namespace wins, otherwise the namespace declared by the
 * workstream registry entry, otherwise the documented `default` fallback.
 */
class WorkstreamNamespaceResolutionTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var workstreamService: WorkstreamService

    @Test
    fun `an explicit requested namespace wins over the declared one`() {
        workstreamService.create(
            scope,
            CreateWorkstreamRequest(slug = scope.workstreamId, name = "Default WS", status = "active", namespaceId = "ns-declared"),
        )

        assertThat(workstreamService.resolveNamespaceId(scope, "ns-explicit")).isEqualTo("ns-explicit")
    }

    @Test
    fun `a blank requested namespace falls back to the declared workstream namespace`() {
        workstreamService.create(
            scope,
            CreateWorkstreamRequest(slug = scope.workstreamId, name = "Default WS", status = "active", namespaceId = "ns-declared"),
        )

        assertThat(workstreamService.resolveNamespaceId(scope, null)).isEqualTo("ns-declared")
        assertThat(workstreamService.resolveNamespaceId(scope, "   ")).isEqualTo("ns-declared")
        assertThat(workstreamService.declaredNamespaceId(scope)).isEqualTo("ns-declared")
    }

    @Test
    fun `an undeclared workstream namespace falls back to the default namespace`() {
        workstreamService.create(
            scope,
            CreateWorkstreamRequest(slug = scope.workstreamId, name = "Default WS", status = "active"),
        )

        assertThat(workstreamService.declaredNamespaceId(scope)).isNull()
        assertThat(workstreamService.resolveNamespaceId(scope, null))
            .isEqualTo(WorkstreamService.DEFAULT_NAMESPACE_ID)
    }

    @Test
    fun `a scope without a registry entry resolves to the default namespace`() {
        val unregistered = TenantScope(scope.organizationId, "ws-no-registry")

        assertThat(workstreamService.declaredNamespaceId(unregistered)).isNull()
        assertThat(workstreamService.resolveNamespaceId(unregistered, null))
            .isEqualTo(WorkstreamService.DEFAULT_NAMESPACE_ID)
    }
}
