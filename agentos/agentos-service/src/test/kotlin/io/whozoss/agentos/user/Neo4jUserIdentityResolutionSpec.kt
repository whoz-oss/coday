package io.whozoss.agentos.user

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSpec
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Integration tests for Story 1.5: User Identity Resolution and Auto-Creation.
 *
 * Verifies that the first user becomes super-admin, subsequent users do not,
 * and resolveOrCreateByExternalId is idempotent — all against a real Neo4j instance.
 */
@SpringBootTest
@ActiveProfiles("test", "neo4j")
class Neo4jUserIdentityResolutionSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun registerNeo4jProperties(registry: DynamicPropertyRegistry) =
            Neo4jContainerSpec.registerProperties(registry)
    }

    @Autowired
    lateinit var userService: UserService

    @Autowired
    lateinit var driver: Driver

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "first user gets isAdmin=true in real Neo4j" {
            val firstUser = userService.resolveOrCreateByExternalId("first@example.com")

            firstUser shouldNotBe null
            firstUser.isAdmin.shouldBeTrue()
            firstUser.externalId shouldBe "first@example.com"
            firstUser.email shouldBe "first@example.com"
        }

        "subsequent users get isAdmin=false" {
            // Create first user (becomes admin)
            val firstUser = userService.resolveOrCreateByExternalId("first@example.com")
            firstUser.isAdmin.shouldBeTrue()

            // Create second user (should NOT be admin)
            val secondUser = userService.resolveOrCreateByExternalId("second@example.com")
            secondUser.isAdmin.shouldBeFalse()
            secondUser.externalId shouldBe "second@example.com"

            // Create third user (also NOT admin)
            val thirdUser = userService.resolveOrCreateByExternalId("third@example.com")
            thirdUser.isAdmin.shouldBeFalse()
        }

        "resolveOrCreateByExternalId is idempotent" {
            // First call - creates the user
            val firstCall = userService.resolveOrCreateByExternalId("idempotent@example.com")
            firstCall shouldNotBe null
            firstCall.isAdmin.shouldBeTrue() // first user

            // Second call - should return same user, not create a new one
            val secondCall = userService.resolveOrCreateByExternalId("idempotent@example.com")
            secondCall.id shouldBe firstCall.id
            secondCall.externalId shouldBe firstCall.externalId
            secondCall.isAdmin shouldBe firstCall.isAdmin
        }

        "email is extracted from externalId when it contains @" {
            val user = userService.resolveOrCreateByExternalId("user@company.com")
            user.email shouldBe "user@company.com"
        }

        "email is empty when externalId is not an email" {
            val user = userService.resolveOrCreateByExternalId("john.doe")
            user.email shouldBe ""
            user.externalId shouldBe "john.doe"
        }

        "user count increases correctly in real Neo4j" {
            userService.count() shouldBe 0L

            userService.resolveOrCreateByExternalId("first@example.com")
            userService.count() shouldBe 1L

            userService.resolveOrCreateByExternalId("second@example.com")
            userService.count() shouldBe 2L

            // Idempotent - should not increase count
            userService.resolveOrCreateByExternalId("first@example.com")
            userService.count() shouldBe 2L
        }
    }
}
