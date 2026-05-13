package io.whozoss.agentos.security.declarative

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

/**
 * Unit tests for [AgentOsAuthenticationFilter].
 *
 * Uses Spring's MockHttpServletRequest/Response rather than mockk because
 * [org.springframework.web.filter.OncePerRequestFilter.doFilter] performs
 * `request.getAttribute(...)` / `setAttribute(...)` lifecycle bookkeeping that
 * mockk's relaxed mocks don't model accurately.
 *
 * The filter clears the [SecurityContextHolder] in a `finally` block after the
 * chain completes (thread-local hygiene), so we capture the populated context
 * via a custom [FilterChain] that snapshots the [Authentication] at the moment
 * the chain is invoked — i.e. the moment when `@PreAuthorize` AOP would see it
 * during a real request.
 */
class AgentOsAuthenticationFilterSpec : StringSpec({

    val userService = mockk<UserService>()
    val filter = AgentOsAuthenticationFilter(userService)

    /** Filter chain that snapshots the SecurityContext at the moment chain.doFilter is invoked. */
    class CapturingChain : FilterChain {
        var capturedAuthentication: Authentication? = null
        override fun doFilter(request: ServletRequest, response: ServletResponse) {
            capturedAuthentication = SecurityContextHolder.getContext().authentication
        }
    }

    fun freshContext() = Triple(MockHttpServletRequest(), MockHttpServletResponse(), CapturingChain())

    beforeTest { SecurityContextHolder.clearContext() }
    afterTest { SecurityContextHolder.clearContext() }

    "non-admin user populates SecurityContext during the chain without ROLE_SUPER_ADMIN" {
        val (request, response, chain) = freshContext()
        val user = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "alice@example.com",
            email = "alice@example.com",
            isAdmin = false,
        )
        every { userService.getCurrentUser() } returns user

        filter.doFilter(request, response, chain)

        val auth = chain.capturedAuthentication
        (auth is AgentOsAuthentication) shouldBe true
        auth!!.name shouldBe user.id.toString()
        (auth.principal as User).id shouldBe user.id
        auth.isAuthenticated shouldBe true
        auth.authorities.shouldBeEmpty()
    }

    "super-admin user populates SecurityContext during the chain with ROLE_SUPER_ADMIN" {
        val (request, response, chain) = freshContext()
        val user = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "root@example.com",
            email = "root@example.com",
            isAdmin = true,
        )
        every { userService.getCurrentUser() } returns user

        filter.doFilter(request, response, chain)

        val auth = chain.capturedAuthentication
        (auth is AgentOsAuthentication) shouldBe true
        auth!!.authorities shouldContain SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
        auth.name shouldBe user.id.toString()
        (auth.principal as User).id shouldBe user.id
    }

    "filter chain continues when getCurrentUser throws (no Authentication captured during chain)" {
        val (request, response, chain) = freshContext()
        every { userService.getCurrentUser() } throws RuntimeException("no identity")

        filter.doFilter(request, response, chain)

        chain.capturedAuthentication shouldBe null
    }

    "Authentication.name returns the User UUID as String (not principal.toString)" {
        val (request, response, chain) = freshContext()
        val userId = UUID.randomUUID()
        val user = User(
            metadata = EntityMetadata(id = userId),
            externalId = "alice@example.com",
            email = "alice@example.com",
            isAdmin = false,
        )
        every { userService.getCurrentUser() } returns user

        filter.doFilter(request, response, chain)

        chain.capturedAuthentication?.name shouldBe userId.toString()
    }

    "SecurityContextHolder is cleared after the chain returns (thread-local hygiene)" {
        val (request, response, chain) = freshContext()
        val user = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "alice@example.com",
            email = "alice@example.com",
            isAdmin = false,
        )
        every { userService.getCurrentUser() } returns user

        filter.doFilter(request, response, chain)

        // Captured during the chain
        chain.capturedAuthentication shouldNotBe null
        // Cleared after the chain
        SecurityContextHolder.getContext().authentication shouldBe null
    }

    "SecurityContextHolder is cleared even when the chain throws" {
        val (request, response, _) = freshContext()
        val throwingChain = FilterChain { _, _ -> throw RuntimeException("downstream blew up") }
        val user = User(
            metadata = EntityMetadata(id = UUID.randomUUID()),
            externalId = "alice@example.com",
            email = "alice@example.com",
            isAdmin = false,
        )
        every { userService.getCurrentUser() } returns user

        runCatching { filter.doFilter(request, response, throwingChain) }

        SecurityContextHolder.getContext().authentication shouldBe null
    }
})

// kotest helper not imported by default
private infix fun Any?.shouldNotBe(expected: Any?) {
    if (this == expected) error("Expected $this to not be $expected")
}
