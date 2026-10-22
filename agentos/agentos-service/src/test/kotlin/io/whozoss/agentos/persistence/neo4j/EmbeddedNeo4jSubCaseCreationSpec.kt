package io.whozoss.agentos.persistence.neo4j

import com.ninjasquad.springmockk.SpykBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseNodeNeo4jRepository
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.caseFlow.Neo4jCaseRepository
import io.whozoss.agentos.delegation.SubCaseManager
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.persistence.Neo4jChildLinkService
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserRepository
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.aop.framework.ProxyFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import java.time.Clock
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

    @Autowired lateinit var caseNodes: CaseNodeNeo4jRepository

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Autowired lateinit var clock: Clock

    private fun count(
        query: String,
        parameters: Map<String, Any>,
    ): Long = driver.session().use { it.run(query, parameters).single().get("n").asLong() }

    private fun countChildren(parent: Case): Long =
        count("MATCH (c:Case {parentCaseId: \$parent}) RETURN count(c) AS n", mapOf("parent" to parent.id.toString()))

    private fun family(): Triple<Namespace, Case, User> {
        val namespace = namespaces.save(Namespace(name = "owner"))
        val user = users.save(User(externalId = "delegator@example.com"))
        return Triple(namespace, caseService.create(Case(namespaceId = namespace.id)), user)
    }

    /** DelegationTool gets the service itself, not its proxy: CaseServiceImpl.runAgent passes `this`. */
    private fun delegate(
        namespace: Namespace,
        parent: Case,
        user: User,
    ) = AopTestUtils
        .getUltimateTargetObject<SubCaseManager>(subCases)
        .startSubCase(parent.id, namespace.id, "agent", "task", user.id)

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

        "a sub-case whose namespace link fails is not persisted, although startSubCase runs without a transaction" {
            val (namespace, parent, user) = family()
            every { childLinkService.link("Case", any(), "Namespace", namespace.id.toString(), any()) } throws
                IllegalStateException("link failed")

            shouldThrow<IllegalStateException> { delegate(namespace, parent, user) }
            countChildren(parent) shouldBe 0L
        }

        "insert rolls a sub-case back when its PARENT_OF edge fails" {
            val (namespace, parent) = family()
            // Spring Data repositories are JDK proxies that mockk cannot spy: wrap the real one instead,
            // under the transaction interceptor Spring applies to the Neo4jCaseRepository bean.
            val failingNodes =
                object : CaseNodeNeo4jRepository by caseNodes {
                    override fun linkParentToChild(
                        parentCaseId: String,
                        childCaseId: String,
                    ): Unit = throw IllegalStateException("edge failed")
                }
            val repository =
                ProxyFactory(Neo4jCaseRepository(failingNodes, childLinkService, clock))
                    .apply {
                        isProxyTargetClass = true
                        addAdvice(TransactionInterceptor(transactionManager, AnnotationTransactionAttributeSource()))
                    }.proxy as CaseRepository

            shouldThrow<IllegalStateException> {
                repository.insert(Case(namespaceId = namespace.id, parentCaseId = parent.id))
            }
            countChildren(parent) shouldBe 0L
        }
    }
}
