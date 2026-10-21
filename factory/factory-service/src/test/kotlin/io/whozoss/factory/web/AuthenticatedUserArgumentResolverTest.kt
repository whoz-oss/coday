package io.whozoss.factory.web

import io.whozoss.factory.error.UnauthenticatedException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.ServletWebRequest

class AuthenticatedUserArgumentResolverTest {

    private val resolver = AuthenticatedUserArgumentResolver()

    @Suppress("unused")
    private object Handlers {
        fun withUser(user: AuthenticatedUser) = user

        fun withString(value: String) = value
    }

    private fun parameter(method: String, type: Class<*>) =
        MethodParameter(Handlers::class.java.getDeclaredMethod(method, type), 0)

    @Test
    fun `supports AuthenticatedUser parameters only`() {
        assertThat(resolver.supportsParameter(parameter("withUser", AuthenticatedUser::class.java))).isTrue()
        assertThat(resolver.supportsParameter(parameter("withString", String::class.java))).isFalse()
    }

    @Test
    fun `resolves the user attached by the filter`() {
        val request = MockHttpServletRequest()
        val user = AuthenticatedUser("alice", "org")
        request.setAttribute(AuthenticationFilter.ATTRIBUTE, user)

        val resolved = resolver.resolveArgument(
            parameter("withUser", AuthenticatedUser::class.java), null, ServletWebRequest(request), null,
        )

        assertThat(resolved).isEqualTo(user)
    }

    @Test
    fun `a request the filter did not authenticate is refused`() {
        assertThatThrownBy {
            resolver.resolveArgument(
                parameter("withUser", AuthenticatedUser::class.java), null,
                ServletWebRequest(MockHttpServletRequest()), null,
            )
        }.isInstanceOf(UnauthenticatedException::class.java)
    }
}
