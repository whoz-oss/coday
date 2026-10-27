package io.whozoss.factory.web.security

import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.config.SecurityMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.springframework.mock.env.MockEnvironment

class SecurityModeBannerTest {

    private fun banner(
        environment: org.springframework.core.env.Environment = MockEnvironment(),
        mode: SecurityMode = SecurityMode.LOCAL,
        host: String = "127.0.0.1",
    ) = SecurityModeBanner(
        environment,
        FactoryProperties(
            bind = FactoryProperties.Bind(host = host),
            security = FactoryProperties.Security(mode = mode),
        ),
    )

    @Test
    fun `startup passes without the legacy settings in either mode`() {
        SecurityMode.entries.forEach { mode ->
            assertThatCode { banner(mode = mode).afterPropertiesSet() }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `the legacy property fails startup whatever its value`() {
        listOf("true", "false", "").forEach { value ->
            val env = MockEnvironment().withProperty("factory.security.insecure-mode", value)

            assertThatThrownBy { banner(env).afterPropertiesSet() }
                .describedAs("value '$value'")
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessage(
                    "FACTORY_INSECURE_MODE / factory.security.insecure-mode was removed. " +
                        "Use FACTORY_SECURITY_MODE=local|auth (default local).",
                )
        }
    }

    @Test
    fun `the legacy environment variable fails startup, an explicit false included`() {
        listOf("false", "true").forEach { value ->
            val env = MockEnvironment().withProperty("FACTORY_INSECURE_MODE", value)

            assertThatThrownBy { banner(env).afterPropertiesSet() }
                .describedAs("value '$value'")
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("FACTORY_SECURITY_MODE")
        }
    }

    @Test
    fun `the legacy variable is detected through the real system environment property source`() {
        val env = StandardEnvironment()
        env.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                mapOf("FACTORY_INSECURE_MODE" to "false"),
            ),
        )
        assertThatThrownBy { banner(env).afterPropertiesSet() }.isInstanceOf(IllegalStateException::class.java)

        val relaxed = StandardEnvironment()
        relaxed.propertySources.replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                mapOf("FACTORY_SECURITY_INSECURE_MODE" to "true"),
            ),
        )
        assertThatThrownBy { banner(relaxed).afterPropertiesSet() }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `an unrelated property source does not trip the check`() {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("other", mapOf("factory.security.mode" to "auth")))

        assertThatCode { banner(env).afterPropertiesSet() }.doesNotThrowAnyException()
    }

    @Test
    fun `a non-loopback bind in local mode warns but never refuses startup`() {
        assertThatCode { banner(host = "0.0.0.0").afterPropertiesSet() }.doesNotThrowAnyException()
    }

    @Test
    fun `loopback detection`() {
        listOf("127.0.0.1", "::1", "[::1]", "localhost", "LOCALHOST", " 127.0.0.1 ").forEach {
            assertThat(SecurityModeBanner.isLoopback(it)).describedAs(it).isTrue()
        }
        listOf("0.0.0.0", "192.168.1.10", "example.com", "::").forEach {
            assertThat(SecurityModeBanner.isLoopback(it)).describedAs(it).isFalse()
        }
    }
}
