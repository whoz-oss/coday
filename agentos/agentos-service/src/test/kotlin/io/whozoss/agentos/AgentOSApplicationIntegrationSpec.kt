package io.whozoss.agentos

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldNotBe
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.security.declarative.OwnershipResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AgentOSApplicationIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var ownershipResolver: OwnershipResolver

    init {
        "context should load successfully" {
            // This test verifies that the Spring application context loads correctly
            // The SpringExtension handles the context lifecycle
        }

        "OwnershipResolver wires without Spring cycle (WZ-31189 follow-up)" {
            ownershipResolver shouldNotBe null
        }
    }
}
