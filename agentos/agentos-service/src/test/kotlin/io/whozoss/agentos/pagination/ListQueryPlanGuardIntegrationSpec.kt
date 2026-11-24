package io.whozoss.agentos.pagination

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.longs.shouldBeLessThan
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.api.user.UsersListFilters
import io.whozoss.agentos.user.usersPageCypher
import io.whozoss.agentos.userGroup.membersPageCypher
import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.neo4j.driver.summary.ProfiledPlan
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

/**
 * Guards the cost of the first page of the list queries by counting the database hits PROFILE reports for the exact
 * statements the repositories run, not by timing them. Db hits depend on the plan and the data, never on machine load.
 *
 * Each test seeds [USERS] users of its own in one statement (and removes them after). The shared database may hold
 * other specs' leftovers, but a correct plan never reads them: it walks the `:User(externalId)` index from the start
 * or starts from the namespace or group, so its hits stay far below the thresholds whatever else is in the graph,
 * while a plan that scans or sorts every user reads at least [USERS] rows several times and trips them.
 *
 * The guard validates the plan on the embedded Neo4j version only.
 */
@SpringBootTest
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class ListQueryPlanGuardIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var driver: Driver

    private fun hits(plan: ProfiledPlan): Long = plan.dbHits() + plan.children().sumOf { hits(it) }

    private fun dbHits(statement: Pair<String, Map<String, Any>>): Long =
        driver.session().use { it.run("PROFILE ${statement.first}", statement.second).consume().profile().let(::hits) }

    private fun run(
        cypher: String,
        vararg params: Any,
    ) = driver.session().use { it.run(cypher, Values.parameters(*params)).consume() }

    private fun withSeededUsers(
        token: String,
        test: () -> Unit,
    ) {
        run("UNWIND range(1, $USERS) AS i CREATE (:User {id: randomUUID(), externalId: 'plan-' + \$t + '-' + right('00000' + toString(i), 5), email: ''})", "t", token)
        try {
            test()
        } finally {
            run("MATCH (u:User) WHERE u.externalId STARTS WITH 'plan-' + \$t DETACH DELETE u", "t", token)
        }
    }

    init {
        "users first page reads the index in order: its db hits do not grow with the number of users" {
            run("CALL db.awaitIndexes()")
            withSeededUsers(UUID.randomUUID().toString()) {
                dbHits(usersPageCypher(UsersListFilters(), null, PAGE)) shouldBeLessThan PAGE_BUDGET
            }
        }

        "users filtered by namespace read the namespace's links, not every user" {
            val t = UUID.randomUUID().toString()
            val namespaceId = UUID.randomUUID()
            withSeededUsers(t) {
                run("CREATE (:Namespace {id: \$n, name: 'plan'})", "n", namespaceId.toString())
                run("MATCH (n:Namespace {id: \$n}) MATCH (u:User) WHERE u.externalId STARTS WITH 'plan-' + \$t WITH n, u ORDER BY u.externalId LIMIT $MEMBERS CREATE (u)-[:MEMBER]->(n) CREATE (u)-[:ADMIN]->(n)", "n", namespaceId.toString(), "t", t)
                try {
                    dbHits(usersPageCypher(UsersListFilters(namespaceId = namespaceId), null, PAGE)) shouldBeLessThan NAMESPACE_BUDGET
                } finally {
                    run("MATCH (n:Namespace {id: \$n}) DETACH DELETE n", "n", namespaceId.toString())
                }
            }
        }

        "group members first page is bounded by the roster, not by the users table" {
            val t = UUID.randomUUID().toString()
            val groupId = UUID.randomUUID()
            withSeededUsers(t) {
                run("CREATE (:UserGroup {id: \$g, name: 'plan'})", "g", groupId.toString())
                run("MATCH (g:UserGroup {id: \$g}) MATCH (u:User) WHERE u.externalId STARTS WITH 'plan-' + \$t WITH g, u ORDER BY u.externalId LIMIT $MEMBERS CREATE (u)-[:MEMBER]->(g)", "g", groupId.toString(), "t", t)
                try {
                    dbHits(membersPageCypher(groupId, null, PAGE)) shouldBeLessThan GROUP_BUDGET
                } finally {
                    run("MATCH (g:UserGroup {id: \$g}) DETACH DELETE g", "g", groupId.toString())
                }
            }
        }
    }

    companion object {
        private const val USERS = 3000
        private const val MEMBERS = 100
        private const val PAGE = 21

        // Measured with the seed above (3000 users, 100 members). Correct plan vs the plan it replaced (label scan + sort, per-user EXISTS):
        // users 170 vs 9,781; namespace 869 vs 7,235. The group query expands its 100 members: 699 hits, where
        // scanning the users table would cost 9,781. Each budget sits between the two.
        private const val PAGE_BUDGET = 1_000L
        private const val NAMESPACE_BUDGET = 3_000L
        private const val GROUP_BUDGET = 2_000L
    }
}
