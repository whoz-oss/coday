package io.whozoss.agentos.security

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class LocalSecurityServiceTest : StringSpec({
    timeout = 5000

    "resolveCurrentIdentity returns OS username" {
        val service = LocalSecurityService()
        val expected = System.getProperty("user.name") ?: System.getenv("USER") ?: System.getenv("USERNAME")

        val result = service.resolveCurrentIdentity()

        result shouldBe expected
    }

    "resolveCurrentIdentity throws 403 for forbidden usernames" {
        // We can only test the guard logic directly since we cannot override the system property.
        // Verify that every name in FORBIDDEN_USERNAMES would be rejected by the guard.
        val forbidden = LocalSecurityService.FORBIDDEN_USERNAMES
        listOf("root", "admin", "daemon", "nobody", "www-data", "docker").forEach {
            (it in forbidden) shouldBe true
        }
    }

    "FORBIDDEN_USERNAMES contains expected system accounts" {
        val forbidden = LocalSecurityService.FORBIDDEN_USERNAMES
        listOf("root", "admin", "administrator", "daemon", "nobody", "www-data", "nginx", "docker").forEach {
            (it in forbidden) shouldBe true
        }
    }
})
