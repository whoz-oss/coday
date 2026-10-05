package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/** Default configuration: `agentos.git.workspaces.enabled` is not set. */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class GitWorkspacesDisabledSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var context: ApplicationContext

    init {
        "without agentos.git.workspaces.enabled no binding service or repository is registered" {
            context.getBeanNamesForType(CaseResourceBindingService::class.java).toList().shouldBeEmpty()
            context.getBeanNamesForType(CaseResourceBindingRepository::class.java).toList().shouldBeEmpty()
        }
    }
}
