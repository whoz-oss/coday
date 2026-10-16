package io.whozoss.factory.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.ServletWebRequest

class TrustContextArgumentResolverTest {

    private val resolver = TrustContextArgumentResolver()

    @Suppress("unused")
    private class Sample {
        fun withContext(trustContext: TrustContext) = trustContext
        fun withString(value: String) = value
    }

    private fun parameter(method: String, vararg types: Class<*>): MethodParameter {
        val declared = Sample::class.java.getDeclaredMethod(method, *types)
        return MethodParameter(declared, 0)
    }

    @Test
    fun `supports TrustContext parameters only`() {
        assertThat(resolver.supportsParameter(parameter("withContext", TrustContext::class.java))).isTrue()
        assertThat(resolver.supportsParameter(parameter("withString", String::class.java))).isFalse()
    }

    @Test
    fun `resolves the trust context attached to the request`() {
        val request = MockHttpServletRequest()
        val context = TrustContext(
            principalId = "p1",
            authenticationMethod = TrustContext.AUTH_JWT,
        )
        request.setAttribute(TrustContextFilter.ATTRIBUTE, context)

        val resolved = resolver.resolveArgument(
            parameter("withContext", TrustContext::class.java),
            null,
            ServletWebRequest(request),
            null,
        )

        assertThat(resolved).isSameAs(context)
    }

    @Test
    fun `resolves to null when no trust context is attached`() {
        val resolved = resolver.resolveArgument(
            parameter("withContext", TrustContext::class.java),
            null,
            ServletWebRequest(MockHttpServletRequest()),
            null,
        )

        assertThat(resolved).isNull()
    }
}
