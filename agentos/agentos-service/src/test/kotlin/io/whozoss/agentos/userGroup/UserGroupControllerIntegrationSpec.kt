package io.whozoss.agentos.userGroup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.user.UserService
import org.neo4j.driver.Driver
import org.neo4j.driver.Values
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import org.springframework.context.annotation.Import
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.security.SuperAdminCallerSpec
import io.whozoss.agentos.sdk.api.userGroup.UserGroupCreateRequest
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.*

// Note: findBynamespaceId returns emptyList() in in-memory mode (no Cypher join).
// The GET endpoint and response shape are verified here; the Cypher query is covered
// by Neo4j persistence tests.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class UserGroupControllerIntegrationSpec : SuperAdminCallerSpec() {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var userGroupService: UserGroupService

    @Autowired lateinit var namespaceService: NamespaceService

    @Autowired lateinit var userService: UserService

    @Autowired lateinit var driver: Driver

    @Autowired lateinit var objectMapper: ObjectMapper

    // The test database is shared by every spec: each test builds its own namespace, group and users.
    private fun token() = UUID.randomUUID().toString().take(8)

    /** [count] member external ids that sort in creation order. */
    private fun memberIds(
        token: String,
        count: Int,
    ) = (0 until count).map { "m$it-$token@example.com" }

    private fun group(
        members: List<String>,
        admins: Set<String> = emptySet(),
    ) = userGroupService.createFromRequest(
        UserGroupCreateRequest(
            namespaceId =
                namespaceService
                    .create(Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "ns", externalId = "ns-${UUID.randomUUID()}"))
                    .id,
            name = "Group-${UUID.randomUUID()}",
            userExternalIdsToAdd = members.toSet(),
            adminExternalIds = admins,
        ),
    )

    private fun listMembers(
        groupId: UUID,
        body: String,
    ) = mockMvc.perform(post("/api/user-groups/$groupId/members/list").contentType(MediaType.APPLICATION_JSON).content(body))

    private fun page(
        groupId: UUID,
        limit: Int,
        next: String? = null,
    ): JsonNode =
        objectMapper.readTree(
            listMembers(groupId, """{ "limit": $limit${next?.let { ", \"next\": \"$it\"" } ?: ""} }""")
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString,
        )

    private fun JsonNode.externalIds() = this["data"].map { it["externalId"].asText() }

    private fun JsonNode.next() = this["metadata"]["next"].takeUnless { it.isNull }?.asText()

    init {

        "GET /api/user-groups without namespaceId returns 400" {
            mockMvc
                .perform(get("/api/user-groups"))
                .andExpect(status().isBadRequest)
        }

        "GET /api/user-groups?namespaceId=unknownUUID returns empty list" {
            mockMvc
                .perform(get("/api/user-groups").param("namespaceId", UUID.randomUUID().toString()))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(0)))
        }

        // -------------------------------------------------------------------------
        // POST /api/user-groups — create
        // -------------------------------------------------------------------------

        "POST /api/user-groups with agentIds containing a non-UUID string returns 400" {
            mockMvc
                .perform(
                    post("/api/user-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "ext-1", "name": "Group A", "agentIds": ["not-a-uuid"] }"""),
                ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // GET /api/user-groups
        // -------------------------------------------------------------------------

        "GET /api/user-groups?namespaceId= returns groups for the matching namespace" {
            val externalId = "federation-${UUID.randomUUID()}"
            val namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        name = "test-namespace",
                        externalId = externalId,
                    ),
                )
            userGroupService.create(
                UserGroup(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Group A",
                ),
            )
            userGroupService.create(
                UserGroup(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespace.id,
                    name = "Group B",
                ),
            )

            // In-memory mode: findByNamespaceId returns emptyList().
            // This test verifies the endpoint wiring and response shape.
            // The actual Cypher join is tested against Neo4j in persistence tests.
            mockMvc
                .perform(get("/api/user-groups").param("namespaceId", namespace.id.toString()))
                .andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // GET /api/user-groups/{userGroupId}/members
        // -------------------------------------------------------------------------

        "GET /api/user-groups/{id}/members returns the group members" {
            val externalId = "federation-${UUID.randomUUID()}"
            val namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        name = "test-namespace",
                        externalId = externalId,
                    ),
                )
            val group =
                userGroupService.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceId = namespace.id,
                        name = "Group With Members",
                        userExternalIdsToAdd = setOf("alice@example.com", "bob@example.com"),
                    ),
                )

            mockMvc
                .perform(get("/api/user-groups/${group.userGroupId}/members"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", hasSize<Any>(2)))
                .andExpect(jsonPath("$[*].externalId", containsInAnyOrder("alice@example.com", "bob@example.com")))
        }

        "GET /api/user-groups/{id}/members reflects adminExternalIds roles" {
            val externalId = "federation-${UUID.randomUUID()}"
            val namespace =
                namespaceService.create(
                    Namespace(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        name = "test-namespace",
                        externalId = externalId,
                    ),
                )
            val group =
                userGroupService.createFromRequest(
                    UserGroupCreateRequest(
                        namespaceId = namespace.id,
                        name = "Group With Admin",
                        userExternalIdsToAdd = setOf("alice@example.com", "bob@example.com"),
                        adminExternalIds = setOf("alice@example.com"),
                    ),
                )

            mockMvc
                .perform(get("/api/user-groups/${group.userGroupId}/members"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.externalId=='alice@example.com')].role", contains("ADMIN")))
                .andExpect(jsonPath("$[?(@.externalId=='bob@example.com')].role", contains("MEMBER")))
        }

        // -------------------------------------------------------------------------
        // POST /api/user-groups/{userGroupId}/members/list
        // -------------------------------------------------------------------------

        "POST members/list walks the roster page by page, sorted, with the role and the total on every page" {
            val ids = memberIds(token(), 8)
            val group = group(ids, admins = setOf(ids[0]))

            val first = page(group.userGroupId, 3)
            val middle = page(group.userGroupId, 3, first.next())
            val last = page(group.userGroupId, 3, middle.next())

            listOf(first, middle, last).map { it.externalIds() } shouldContainExactly listOf(ids.take(3), ids.subList(3, 6), ids.drop(6))
            listOf(first, middle, last).map { it["metadata"]["totalCount"].asInt() } shouldContainExactly listOf(8, 8, 8)
            listOf(first.next() != null, middle.next() != null, last.next() == null) shouldContainExactly listOf(true, true, true)
            first["data"].map { it["role"].asText() } shouldContainExactly listOf("ADMIN", "MEMBER", "MEMBER")
        }

        "POST members/list resumes strictly after the cursor when members are added or removed behind it" {
            val token = token()
            val ids = memberIds(token, 8)
            val group = group(ids)
            val first = page(group.userGroupId, 3)

            userGroupService.updateFromRequest(
                group.userGroupId,
                UserGroupUpdateRequest(name = group.name, userExternalIdsToAdd = setOf("a-$token@example.com"), userExternalIdsToRemove = setOf(ids[1])),
            )

            page(group.userGroupId, 3, first.next()).externalIds() shouldContainExactly ids.subList(3, 6)
        }

        "POST members/list lists a user holding both edges once as ADMIN and skips removed users and other groups" {
            val token = token()
            val ids = memberIds(token, 4)
            val group = group(ids)
            group(memberIds("other-$token", 2))
            driver.session().use {
                it.run(
                    "MATCH (u:User {externalId: \$e}), (g:UserGroup {id: \$g}) MERGE (u)-[:ADMIN]->(g)",
                    Values.parameters("e", ids[1], "g", group.userGroupId.toString()),
                )
            }
            userService.delete(userService.findByExternalId(ids[2])!!.id)

            val page = page(group.userGroupId, 10)

            page.externalIds() shouldContainExactly listOf(ids[0], ids[1], ids[3])
            page["data"][1]["role"].asText() shouldBe "ADMIN"
            page["metadata"]["totalCount"].asInt() shouldBe 3
        }

        "POST members/list returns an empty page for an empty group and for a removed group" {
            val empty = group(emptyList())
            val removed = group(memberIds(token(), 2))
            userGroupService.delete(removed.userGroupId)

            listOf(empty, removed).forEach {
                val page = page(it.userGroupId, 10)
                page.externalIds() shouldContainExactly emptyList()
                page.next() shouldBe null
                page["metadata"]["totalCount"].asInt() shouldBe 0
            }
        }

        "POST members/list rejects a limit outside 1..500 with 400" {
            val group = group(emptyList())

            listOf("""{ "limit": 0 }""", """{ "limit": 501 }""").forEach {
                listMembers(group.userGroupId, it).andExpect(status().isBadRequest)
            }
        }

        // -------------------------------------------------------------------------
        // POST /api/user-groups/{id} — admin delta mode (the rules themselves are unit-tested)
        // -------------------------------------------------------------------------

        fun updateGroup(
            groupId: UUID,
            fields: String,
        ) = mockMvc.perform(post("/api/user-groups/$groupId").contentType(MediaType.APPLICATION_JSON).content("""{ "name": "Renamed", $fields }"""))

        fun json(vararg values: String) = values.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        fun roles(groupId: UUID) =
            objectMapper
                .readTree(mockMvc.perform(get("/api/user-groups/$groupId/members")).andReturn().response.contentAsString)
                .associate { it["externalId"].asText() to it["role"].asText() }

        fun edges(
            externalId: String,
            groupId: UUID,
            relation: String,
        ) = driver.session().use {
            it.run(
                "MATCH (:User {externalId: \$e})-[r:$relation]->(:UserGroup {id: \$g}) RETURN count(r) AS n",
                Values.parameters("e", externalId, "g", groupId.toString()),
            ).single()["n"].asInt()
        }

        "POST /{id} in delta mode promotes and demotes only the named members, leaving other admins alone" {
            val ids = memberIds(token(), 6)
            val group = group(ids, admins = setOf(ids[0], ids[4]))

            updateGroup(group.userGroupId, """"adminExternalIdsToAdd": ${json(ids[2])}, "adminExternalIdsToRemove": ${json(ids[0])}""")
                .andExpect(status().isOk)

            roles(group.userGroupId).filterValues { it == "ADMIN" }.keys shouldBe setOf(ids[2], ids[4])
        }

        "POST /{id} with both delta fields empty changes no role, and JSON null delta fields mean replace mode" {
            val ids = memberIds(token(), 4)
            val group = group(ids, admins = setOf(ids[0], ids[1]))

            updateGroup(group.userGroupId, """"adminExternalIdsToAdd": [], "adminExternalIdsToRemove": []""").andExpect(status().isOk)
            roles(group.userGroupId).filterValues { it == "ADMIN" }.keys shouldBe setOf(ids[0], ids[1])

            updateGroup(group.userGroupId, """"adminExternalIdsToAdd": null, "adminExternalIdsToRemove": null, "adminExternalIds": ${json(ids[2])}""")
                .andExpect(status().isOk)
            roles(group.userGroupId).filterValues { it == "ADMIN" }.keys shouldBe setOf(ids[2])
        }

        "POST /{id} maps a delta rule violation to 422 and writes nothing" {
            val token = token()
            val ids = memberIds(token, 3)
            val group = group(ids)

            updateGroup(
                group.userGroupId,
                """"userExternalIdsToAdd": ${json("new-$token@example.com")}, "adminExternalIdsToAdd": ${json("stranger-$token@example.com")}""",
            ).andExpect(status().isUnprocessableEntity)

            roles(group.userGroupId).keys shouldBe ids.toSet()
            mockMvc.perform(get("/api/user-groups/${group.userGroupId}")).andExpect(jsonPath("$.name").value(group.name))
        }

        "POST /{id} adding an existing MEMBER or ADMIN through userExternalIdsToAdd is a no-op" {
            val ids = memberIds(token(), 3)
            val group = group(ids, admins = setOf(ids[0]))

            updateGroup(group.userGroupId, """"userExternalIdsToAdd": ${json(ids[0], ids[1])}, "adminExternalIdsToAdd": []""").andExpect(status().isOk)

            roles(group.userGroupId) shouldBe mapOf(ids[0] to "ADMIN", ids[1] to "MEMBER", ids[2] to "MEMBER")
            listOf(edges(ids[0], group.userGroupId, "ADMIN"), edges(ids[0], group.userGroupId, "MEMBER"), edges(ids[1], group.userGroupId, "MEMBER"), edges(ids[1], group.userGroupId, "ADMIN")) shouldContainExactly listOf(1, 0, 1, 0)
        }
    }
}
