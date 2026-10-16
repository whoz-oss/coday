package io.whozoss.factory.web

import io.whozoss.factory.config.FactoryProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkstreamServiceAccessRulesTest {

    private val token = "w".repeat(40)

    private fun security(
        workstream: FactoryProperties.WorkstreamServiceAccess,
        worker: FactoryProperties.HttpApiSubmission = FactoryProperties.HttpApiSubmission(),
    ) = FactoryProperties.Security(httpApiSubmission = worker, workstreamService = workstream)

    private fun valid() = FactoryProperties.WorkstreamServiceAccess(
        enabled = true,
        bearerToken = token,
        serviceIdentityId = "agentos-workstream",
        organizationId = "org-1",
        workstreamId = "ws-1",
        permissions = setOf("read"),
    )

    @Test
    fun `a complete configuration is usable`() {
        assertThat(WorkstreamServiceAccessRules.problems(security(valid()))).isEmpty()
        assertThat(WorkstreamServiceAccessRules.isUsable(security(valid()))).isTrue()
    }

    @Test
    fun `a disabled identity is never usable even when otherwise valid`() {
        assertThat(WorkstreamServiceAccessRules.isUsable(security(valid().copy(enabled = false)))).isFalse()
    }

    @Test
    fun `every missing or invalid field fails closed`() {
        val cases = mapOf(
            "missing bearer" to valid().copy(bearerToken = ""),
            "short bearer" to valid().copy(bearerToken = "too-short"),
            "whitespace-wrapped bearer" to valid().copy(bearerToken = " $token "),
            "missing organization" to valid().copy(organizationId = ""),
            "missing workstream" to valid().copy(workstreamId = " "),
            "empty permissions" to valid().copy(permissions = emptySet()),
            "unknown permission" to valid().copy(permissions = setOf("read", "admin")),
            "unsafe identity" to valid().copy(serviceIdentityId = "bad identity!"),
        )
        cases.forEach { (name, access) ->
            assertThat(WorkstreamServiceAccessRules.problems(security(access))).describedAs(name).isNotEmpty()
            assertThat(WorkstreamServiceAccessRules.isUsable(security(access))).describedAs(name).isFalse()
        }
    }

    @Test
    fun `the workstream credential can never equal the worker credential or identity`() {
        val sameToken = security(
            valid(),
            FactoryProperties.HttpApiSubmission(enabled = true, bearerToken = token, serviceIdentityId = "worker"),
        )
        assertThat(WorkstreamServiceAccessRules.isUsable(sameToken)).isFalse()

        val sameIdentity = security(
            valid(),
            FactoryProperties.HttpApiSubmission(enabled = true, bearerToken = "x".repeat(40), serviceIdentityId = "agentos-workstream"),
        )
        assertThat(WorkstreamServiceAccessRules.isUsable(sameIdentity)).isFalse()
    }
}
