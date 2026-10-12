package io.whozoss.agentos.git

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.agent.RunIntegrationCustomizer
import io.whozoss.agentos.caseFlow.CaseLaunchGate
import io.whozoss.agentos.caseFlow.CaseWorkspaceProvisioning
import io.whozoss.agentos.exchange.DefaultExchangeRootResolver
import io.whozoss.agentos.exchange.ExchangeRootResolver
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** Default configuration: `agentos.git.workspaces.enabled` is not set. */
@SpringBootTest
@AutoConfigureMockMvc
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

    @Autowired
    lateinit var mockMvc: MockMvc

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
                session.run("SHOW INDEXES YIELD name WHERE name STARTS WITH 'case_resource_binding' RETURN count(*) AS n")
                    .single()["n"].asInt() shouldBe 0
            }
        }

        "without agentos.git.workspaces.enabled no workspace provisioning, cleanup or status bean is loaded" {
            listOf(
                CaseWorkspaceProvisioning::class,
                GitExchangeRootResolver::class,
                CaseWorkspaceController::class,
                CaseWorktreeProvisioner::class,
                CaseWorkspaceSweep::class,
                GitWorkspaceLifecycleService::class,
                WorktreeSetupRunner::class,
                WorkspaceProcessGuard::class,
                GitWorkspaceStatusService::class,
            ).forEach { type -> context.getBeanNamesForType(type.java).toList().shouldBeEmpty() }
        }

        "without agentos.git.workspaces.enabled a GIT integration reaches runs as configured" {
            context.getBeanNamesForType(RunIntegrationCustomizer::class.java).toList().shouldBeEmpty()
        }

        "without agentos.git.workspaces.enabled the workspace routes answer 404" {
            val id = UUID.randomUUID()
            mockMvc.perform(get("/api/cases/$id/workspace")).andExpect(status().isNotFound)
            mockMvc.perform(get("/api/namespaces/$id/workspaces")).andExpect(status().isNotFound)
            mockMvc
                .perform(post("/api/cases/$id/workspace/retry").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound)
        }
    }
}
