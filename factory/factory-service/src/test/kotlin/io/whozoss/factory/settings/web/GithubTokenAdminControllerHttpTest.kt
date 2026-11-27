package io.whozoss.factory.settings.web

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.error.FactoryExceptionHandler
import io.whozoss.factory.settings.FactoryGitHubSettingsDto
import io.whozoss.factory.settings.GitHubTokenSettingsService
import io.whozoss.factory.settings.InvalidGitHubTokenException
import io.whozoss.factory.web.AuthenticatedUserArgumentResolver
import io.whozoss.factory.web.AuthenticationFilter
import io.whozoss.factory.web.LocalDevMembershipResolver
import io.whozoss.factory.web.security.LocalIdentityResolver
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder

/** HTTP mapping of `/api/factory/admin/settings/github-token` through the real filter chain, with a stubbed service. */
class GithubTokenAdminControllerHttpTest {

    private val service = mockk<GitHubTokenSettingsService>()
    private val url = "/api/factory/admin/settings/github-token"
    private val connected = FactoryGitHubSettingsDto(
        configured = true, connected = true, status = "connected", source = "admin",
        login = "octocat", maskedToken = "ghp_…6789", checkedAt = "2026-01-01T00:00:00Z",
    )
    private val mvc = MockMvcBuilders.standaloneSetup(GithubTokenAdminController(service))
        .setControllerAdvice(FactoryExceptionHandler())
        .setCustomArgumentResolvers(AuthenticatedUserArgumentResolver())
        .addFilters<StandaloneMockMvcBuilder>(
            AuthenticationFilter(LocalIdentityResolver { "alice" }, LocalDevMembershipResolver()),
        )
        .build()

    @Test
    fun `GET returns the status in the data envelope`() {
        every { service.status() } returns connected

        mvc.perform(get(url))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("connected"))
            .andExpect(jsonPath("$.data.connected").value(true))
            .andExpect(jsonPath("$.data.source").value("admin"))
            .andExpect(jsonPath("$.data.login").value("octocat"))
            .andExpect(jsonPath("$.data.maskedToken").value("ghp_…6789"))
            .andExpect(jsonPath("$.data.token").doesNotExist())
    }

    @Test
    fun `PUT passes the token and the caller to the service and never echoes the token`() {
        every { service.save("ghp_secret_value", "alice") } returns connected

        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("""{"token":"ghp_secret_value"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("connected"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("ghp_secret_value"))))
        verify(exactly = 1) { service.save("ghp_secret_value", "alice") }
    }

    @Test
    fun `PUT of a rejected token answers 200 with the invalid status`() {
        every { service.save(any(), any()) } returns FactoryGitHubSettingsDto(
            configured = true, connected = false, status = "invalid", source = "admin",
            message = "GitHub rejected this token (HTTP 401).",
        )

        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content("""{"token":"ghp_bad"}"""))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("invalid"))
            .andExpect(jsonPath("$.data.connected").value(false))
    }

    @Test
    fun `PUT of a blank or missing token is a 400 INVALID_VALUE`() {
        every { service.save(any(), any()) } throws InvalidGitHubTokenException()

        listOf("""{"token":""}""", """{}""", """{"token":42}""").forEach { body ->
            mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_VALUE"))
        }
    }

    @Test
    fun `DELETE clears the admin token and returns the fallback status`() {
        every { service.clear("alice") } returns FactoryGitHubSettingsDto(
            configured = false, connected = false, status = "unconfigured", source = "none",
        )

        mvc.perform(delete(url))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("unconfigured"))
        verify(exactly = 1) { service.clear("alice") }
    }

    @Test
    fun `POST test re-verifies without the cache`() {
        every { service.status(true) } returns connected

        mvc.perform(post("$url/test"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.status").value("connected"))
        verify(exactly = 1) { service.status(true) }
    }
}
