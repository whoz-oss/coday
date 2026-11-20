package io.whozoss.agentos.user

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionRelation
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.security.SuperAdminCallerSpec
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * `POST /api/users/list` against the embedded Neo4j harness, as a super-admin (the 403 is in
 * [UserControllerSelfRuleIntegrationSpec]). A new spec rather than a case in an existing one: the existing user specs
 * either mock [UserService] or wipe the database, and these tests need real users, namespaces and links.
 *
 * The database is shared by every spec, so each test scopes its listing to its own namespace or its own random token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class UsersListIntegrationSpec : SuperAdminCallerSpec() {
    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var userService: UserService

    @Autowired lateinit var namespaceService: NamespaceService

    @Autowired lateinit var permissionService: PermissionService

    @Autowired lateinit var driver: Driver

    @Autowired lateinit var objectMapper: ObjectMapper

    private fun token() = UUID.randomUUID().toString().take(8)

    private fun namespace() =
        namespaceService.create(Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "ns", externalId = "ns-${UUID.randomUUID()}"))

    private fun user(
        externalId: String,
        email: String = "",
        firstname: String? = null,
        lastname: String? = null,
    ) = userService.create(User(metadata = EntityMetadata(id = UUID.randomUUID()), externalId = externalId, email = email, firstname = firstname, lastname = lastname))

    private fun link(
        user: User,
        namespace: Namespace,
        relation: PermissionRelation,
    ) = permissionService.grantPermission(user.id.toString(), EntityType.NAMESPACE, namespace.id.toString(), relation)

    private fun list(body: String): JsonNode =
        objectMapper.readTree(
            mockMvc
                .perform(post("/api/users/list").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun JsonNode.externalIds() = this["data"].map { it["externalId"].asText() }

    private fun JsonNode.next() = this["metadata"]["next"].takeUnless { it.isNull }?.asText()

    private fun JsonNode.total() = this["metadata"]["totalCount"].asInt()

    private fun body(
        namespace: Namespace? = null,
        query: String? = null,
        limit: Int? = null,
        next: String? = null,
    ): String {
        val filters = listOfNotNull(namespace?.let { "\"namespaceId\": \"${it.id}\"" }, query?.let { "\"query\": ${objectMapper.writeValueAsString(it)}" })
        return listOfNotNull(
            limit?.let { "\"limit\": $it" },
            next?.let { "\"next\": \"$it\"" },
            "\"filters\": { ${filters.joinToString()} }",
        ).joinToString(prefix = "{ ", postfix = " }")
    }

    init {
        "walks a namespace's direct ADMIN and MEMBER users page by page, listing a dual-link user once and skipping removed users" {
            val t = token()
            val ns = namespace()
            val users = (0 until 8).map { user("u$it-$t") }
            users.forEachIndexed { i, u -> link(u, ns, if (i % 3 == 0) PermissionRelation.ADMIN else PermissionRelation.MEMBER) }
            driver.session().use {
                it.run("MATCH (u:User {externalId: \$e}), (n:Namespace {id: \$n}) MERGE (u)-[:ADMIN]->(n) MERGE (u)-[:MEMBER]->(n)", Values.parameters("e", users[1].externalId, "n", ns.id.toString()))
            }
            link(user("outsider-$t"), namespace(), PermissionRelation.MEMBER)
            userService.delete(users[4].id)
            val ids = users.map { it.externalId }

            val first = list(body(ns, limit = 3))
            userService.delete(users[0].id) // behind the cursor: must not shift the next page
            val middle = list(body(ns, limit = 3, next = first.next()))
            val last = list(body(ns, limit = 3, next = middle.next()))

            first.externalIds() shouldContainExactly listOf(ids[0], ids[1], ids[2])
            middle.externalIds() shouldContainExactly listOf(ids[3], ids[5], ids[6])
            last.externalIds() shouldContainExactly listOf(ids[7])
            listOf(first.total(), last.next() == null, first.next() != null) shouldContainExactly listOf(7, true, true)
        }

        "the default view, with no filter, walks every page in ascending order, listing each seeded user once in order, and ends" {
            val prefix = "dflt-${token()}-"
            val ids = (0 until 7).map { user("$prefix$it").externalId }
            val seen = mutableListOf<String>()

            val first = list(body(limit = 3))
            val maxPages = first.total() / 3 + 2 // the walk must end within the table size: a cursor bug fails instead of hanging
            var page = first
            var pages = 1
            while (true) {
                seen += page.externalIds()
                val next = page.next() ?: break
                check(++pages <= maxPages) { "walk exceeded $maxPages pages for ${first.total()} users: the cursor does not advance" }
                page = list(body(limit = 3, next = next))
            }

            seen.filter { it.startsWith(prefix) } shouldContainExactly ids
        }

        "a removed or unknown namespace gives an empty page with a zero total" {
            val removed = namespace()
            link(user("member-${token()}"), removed, PermissionRelation.MEMBER)
            namespaceService.delete(removed.id)

            listOf(removed, Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "unknown")).forEach {
                val page = list(body(it))
                listOf(page.externalIds(), page.total(), page.next()) shouldContainExactly listOf(emptyList<String>(), 0, null)
            }
        }

        "query matches email, externalId, firstname and lastname case-insensitively, whatever the null name properties" {
            val t = token()
            val a = user("1-a-$t", email = "Zq$t@mail.io") // names stay null: a null must not break the OR chain
            val b = user("2-ZQ$t")
            val c = user("3-c-$t", firstname = "xZq$t")
            val d = user("4-d-$t", lastname = "Zq$t y")
            user("5-f-$t", email = "other@mail.io")

            val page = list(body(query = "zQ$t"))

            page.externalIds() shouldContainExactly listOf(a, b, c, d).map { it.externalId }
            page.total() shouldBe 4
        }

        "query spanning first and last name matches the full name in both orders, and the single fields still match" {
            val t = token()
            val full = user("1-a-$t", firstname = "Jane$t", lastname = "Smith")
            val firstOnly = user("2-b-$t", firstname = "Jane$t") // a null name must not leave a stray space in the full name
            val otherLast = user("3-c-$t", firstname = "Jane$t", lastname = "Doe")
            user("4-d-$t", lastname = "Smith$t")

            val fullName = list(body(query = "jane$t smith"))

            fullName.externalIds() shouldContainExactly listOf(full.externalId)
            fullName.total() shouldBe 1
            list(body(query = "SMITH JANE$t")).externalIds() shouldContainExactly listOf(full.externalId)
            list(body(query = "e$t sm")).externalIds() shouldContainExactly listOf(full.externalId)
            list(body(query = "Jane$t")).externalIds() shouldContainExactly listOf(full, firstOnly, otherLast).map { it.externalId }
            list(body(query = "jane$t  smith")).externalIds() shouldContainExactly emptyList()
        }

        "query is matched literally as a bound parameter: wildcards, injection and parameter syntax match nothing" {
            val t = token()
            val ns = namespace()
            val literal = user("lit-$t", lastname = "O'Brien.*$t")
            val other = user("oth-$t", lastname = "plain$t")
            link(literal, ns, PermissionRelation.MEMBER)
            link(other, ns, PermissionRelation.MEMBER)

            list(body(ns, query = ".*$t")).externalIds() shouldContainExactly listOf(literal.externalId)
            listOf("pla.n$t", "^plain$t$", "plain$t|lit-$t", "x' OR true OR u.externalId CONTAINS '", "\"}) DETACH DELETE u //", "\$query", "\$namespaceId").forEach {
                list(body(ns, query = it)).externalIds() shouldContainExactly emptyList()
            }
            list(body(ns)).externalIds() shouldContainExactly listOf(literal.externalId, other.externalId) // nothing was deleted
        }

        "a blank query applies no filter and namespaceId and query apply together, totals included" {
            val t = token()
            val ns = namespace()
            val hit = user("ab-$t")
            val miss = user("zz-$t")
            link(hit, ns, PermissionRelation.ADMIN)
            link(miss, ns, PermissionRelation.MEMBER)
            link(user("ab2-$t"), namespace(), PermissionRelation.MEMBER)

            list(body(ns, query = "  ")).externalIds() shouldContainExactly listOf(hit.externalId, miss.externalId)
            val both = list(body(ns, query = "ab-$t"))
            both.externalIds() shouldContainExactly listOf(hit.externalId)
            both.total() shouldBe 1
        }

        "rejects a limit outside 1..500 and a bad cursor with 400" {
            listOf("""{ "limit": 0 }""", """{ "limit": 501 }""", """{ "next": "not-a-cursor!" }""").forEach {
                mockMvc.perform(post("/api/users/list").contentType(MediaType.APPLICATION_JSON).content(it)).andExpect(status().isBadRequest)
            }
        }
    }
}
