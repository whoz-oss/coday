package io.whozoss.agentos

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class AgentOSApplicationIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    init {
        "context should load successfully" {
            // This test verifies that the Spring application context loads correctly
            // The SpringExtension handles the context lifecycle
        }
    }
}
