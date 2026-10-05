package io.whozoss.agentos.git

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.persistence.neo4j.Neo4jContainerSupport
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** The Git worker without `agentos.git.workspaces.enabled`: it starts and prepares namespace checkouts only. */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
@TestPropertySource(properties = ["agentos.git.worker.enabled=true", "agentos.git.worker.initial-delay-ms=3600000"])
class GitWorkerWithoutWorkspacesSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    lateinit var context: ApplicationContext

    @Autowired
    lateinit var worker: CaseWorkspaceWorker

    @Autowired
    lateinit var namespaces: NamespaceRepository

    @Autowired
    lateinit var checkouts: RepositoryCheckoutService

    @Autowired
    lateinit var meters: MeterRegistry

    @Autowired
    lateinit var driver: Driver

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "the worker starts without any workspace bean" {
            context.getBeanNamesForType(CaseResourceBindingService::class.java).toList().shouldBeEmpty()
            context.getBeanNamesForType(CaseResourceBindingRepository::class.java).toList().shouldBeEmpty()
            context.getBeanNamesForType(CaseWorktreeProvisioner::class.java).toList().shouldBeEmpty()
            context.getBeanNamesForType(CaseWorkspaceSweep::class.java).toList().shouldBeEmpty()
        }

        "the sweep still prepares namespace checkouts" {
            val namespace = namespaces.save(Namespace(name = "worker-without-workspaces"))
            checkouts.create(
                RepositoryCheckout(
                    namespaceId = namespace.id,
                    integrationConfigId = UUID.randomUUID(),
                    repositoryUrl = "https://example.com/repo.git",
                    mainBranch = "main",
                ),
            )

            worker.provisionPending()

            eventually(10.seconds) {
                checkouts.findByNamespaceId(namespace.id).shouldNotBeNull().status shouldBe RepositoryCheckoutStatus.FAILED
            }
            eventually(10.seconds) { worker.activity().sweeping shouldBe false }
            checkouts.findByNamespaceId(namespace.id)!!.failureReason shouldBe
                "The namespace is no longer associated with a repository"
            meters.counter(CaseWorkspaceWorker.ERROR_COUNTER, "operation", "sweep").count() shouldBe 0.0
        }
    }
}
