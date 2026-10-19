package io.whozoss.agentos.persistence.neo4j

import com.ninjasquad.springmockk.SpykBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.delegation.SubCaseManager
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.AopTestUtils
import java.util.UUID

/** Sub-case creation, against the embedded Neo4j engine. */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class EmbeddedNeo4jSubCaseCreationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var subCases: SubCaseManager

    @Autowired lateinit var caseService: CaseService

    @Autowired lateinit var cases: CaseRepository

    @Autowired lateinit var namespaces: NamespaceRepository

    @Autowired lateinit var users: UserRepository

    @Autowired lateinit var driver: Driver

    @SpykBean lateinit var childLinkService: Neo4jChildLinkService

    private fun count(
        query: String,
        parameters: Map<String, Any>,
    ): Long = driver.session().use { it.run(query, parameters).single().get("n").asLong() }

    init {
        beforeEach { Neo4jContainerSupport.clearDatabase(driver) }

        "a sub-case is inserted with the PARENT_OF edge from its parent" {
            val namespace = namespaces.save(Namespace(name = "owner"))
            val parent = caseService.create(Case(namespaceId = namespace.id))
            val child = cases.insert(Case(namespaceId = namespace.id, parentCaseId = parent.id))
            count(
                "MATCH (:Case {id: \$parent})-[:PARENT_OF]->(:Case {id: \$child}) RETURN count(*) AS n",
                mapOf("parent" to parent.id.toString(), "child" to child.id.toString()),
            ) shouldBe 1L
        }

        "a sub-case whose links fail is not persisted, although startSubCase runs without a transaction" {
            val namespace = namespaces.save(Namespace(name = "owner"))
            val user = users.save(User(externalId = "delegator@example.com"))
            val parent = caseService.create(Case(namespaceId = namespace.id))
            every { childLinkService.link("Case", any(), "Namespace", namespace.id.toString(), any()) } throws
                IllegalStateException("link failed")

            // DelegationTool gets the service itself, not its proxy: CaseServiceImpl.runAgent passes `this`.
            val delegating = AopTestUtils.getUltimateTargetObject<SubCaseManager>(subCases)
            shouldThrow<IllegalStateException> {
                delegating.startSubCase(parent.id, namespace.id, "agent", "task", user.id)
            }
            count(
                "MATCH (c:Case {parentCaseId: \$parent}) RETURN count(c) AS n",
                mapOf("parent" to parent.id.toString()),
            ) shouldBe 0L
        }
    }
}
