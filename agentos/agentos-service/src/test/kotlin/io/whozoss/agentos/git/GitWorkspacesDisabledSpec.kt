package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.caseFlow.CaseLaunchGate
import io.whozoss.agentos.exchange.DefaultExchangeRootResolver
import io.whozoss.agentos.exchange.ExchangeRootResolver
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.neo4j.driver.Driver
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

    @Autowired
    lateinit var exchangeRootResolver: ExchangeRootResolver

    @Autowired
    lateinit var driver: Driver

    init {
        "without agentos.git.workspaces.enabled no binding service or repository is registered" {
            context.getBeanNamesForType(CaseResourceBindingService::class.java).toList().shouldBeEmpty()
            context.getBeanNamesForType(CaseResourceBindingRepository::class.java).toList().shouldBeEmpty()
        }

        "without agentos.git.workspaces.enabled no launch gate is installed and cases keep their own Exchange directory" {
            context.getBeanNamesForType(CaseLaunchGate::class.java).toList().shouldBeEmpty()
            exchangeRootResolver.shouldBeInstanceOf<DefaultExchangeRootResolver>()
        }

        "without agentos.git.workspaces.enabled the binding schema is not created" {
            context.getBeanNamesForType(CaseResourceBindingSchemaInitializer::class.java).toList().shouldBeEmpty()
            driver.session().use { session ->
                session.run("SHOW CONSTRAINTS YIELD name WHERE name STARTS WITH 'case_resource_binding' RETURN count(*) AS n")
                    .single()["n"].asInt() shouldBe 0
            }
        }
    }
}
