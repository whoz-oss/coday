package io.whozoss.factory.web

import io.whozoss.factory.error.ForbiddenAdminRequiredException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdminGuardTest {

    private val guard = AdminGuard()

    @Test
    fun `missing context fails closed`() {
        val decision = guard.checkAdminAuthorization(null)
        assertThat(decision.authorized).isFalse()
        assertThat(decision.reason).isEqualTo(AdminGuard.MISSING_TRUST_CONTEXT)
    }

    @Test
    fun `anonymous context fails closed`() {
        val decision = guard.checkAdminAuthorization(TrustContext.anonymous())
        assertThat(decision.authorized).isFalse()
        assertThat(decision.reason).isEqualTo(AdminGuard.UNAUTHENTICATED)
    }

    @Test
    fun `member context is not an admin`() {
        val context = TrustContext(
            principalId = "u1",
            authenticationMethod = TrustContext.AUTH_JWT,
            roles = listOf("dev"),
        )
        val decision = guard.checkAdminAuthorization(context)
        assertThat(decision.authorized).isFalse()
        assertThat(decision.reason).isEqualTo(AdminGuard.INSUFFICIENT_ADMIN_PERMISSIONS)
    }

    @Test
    fun `admin role grants admin`() {
        val context = TrustContext(
            principalId = "u1",
            authenticationMethod = TrustContext.AUTH_JWT,
            roles = listOf("admin"),
        )
        assertThat(guard.checkAdminAuthorization(context).authorized).isTrue()
    }

    @Test
    fun `uppercase ADMIN role is normalized and grants admin`() {
        val context = TrustContext(
            principalId = "u1",
            authenticationMethod = TrustContext.AUTH_JWT,
            roles = listOf("ADMIN"),
        )
        assertThat(guard.checkAdminAuthorization(context).authorized).isTrue()
    }

    @Test
    fun `admin scope grants admin`() {
        val context = TrustContext(
            principalId = "svc",
            authenticationMethod = TrustContext.AUTH_JWT,
            scopes = listOf(TrustContext.ADMIN_SCOPE),
        )
        assertThat(guard.checkAdminAuthorization(context).authorized).isTrue()
    }

    @Test
    fun `loopback-dev wildcard scope grants admin`() {
        val context = TrustContext(
            principalId = TrustContext.LOOPBACK_DEV_PRINCIPAL_ID,
            authenticationMethod = TrustContext.AUTH_LOOPBACK_DEV,
            scopes = listOf(TrustContext.ADMIN_WILDCARD_SCOPE),
        )
        assertThat(guard.checkAdminAuthorization(context).authorized).isTrue()
    }

    @Test
    fun `requireAdminRole throws a 403 FORBIDDEN_ADMIN_REQUIRED exception`() {
        assertThatThrownBy { guard.requireAdminRole(null) }
            .isInstanceOf(ForbiddenAdminRequiredException::class.java)
            .extracting("statusCode", "errorCode")
            .containsExactly(403, "FORBIDDEN_ADMIN_REQUIRED")
    }

    @Test
    fun `requireAdminRole returns true for an admin`() {
        val context = TrustContext(
            principalId = "u1",
            authenticationMethod = TrustContext.AUTH_LOOPBACK_DEV,
            scopes = listOf("*"),
        )
        assertThat(guard.requireAdminRole(context)).isTrue()
    }
}
