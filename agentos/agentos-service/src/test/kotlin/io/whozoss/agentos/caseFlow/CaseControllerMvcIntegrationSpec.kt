package io.whozoss.agentos.caseFlow

import io.kotest.matchers.shouldBe

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.aiModel.AiModelService
import io.whozoss.agentos.aiProvider.AiProviderService
import io.whozoss.agentos.sdk.aiProvider.AiApiType
import io.whozoss.agentos.sdk.aiProvider.AiModel
import io.whozoss.agentos.sdk.aiProvider.AiProvider
import io.whozoss.agentos.permissions.PermissionNodeNeo4jRepository
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.actor.Actor
import io.whozoss.agentos.sdk.actor.ActorRole
import io.whozoss.agentos.sdk.caseEvent.CaseStatusEvent
import io.whozoss.agentos.sdk.caseEvent.MessageContent
import io.whozoss.agentos.sdk.caseEvent.MessageEvent
import io.whozoss.agentos.sdk.caseEvent.WarnEvent
import io.whozoss.agentos.sdk.caseFlow.CaseStatus
import io.whozoss.agentos.security.SuperAdminCallerSpec
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.UserRepository
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [CaseController].
 *
 * The "test" profile uses [io.whozoss.agentos.permissions.InMemoryPermissionServiceImpl],
 * a permissive no-op stub: every `hasPermission` returns true. Negative paths
 * (403 without namespace access) are therefore covered in [CaseControllerSpec]
 * at the unit level, not here.
 *
 * This suite focuses on:
 * - Bean Validation (`namespaceId` required)
 * - Successful POST /api/cases returns 201 with correct body shape
 * - The JSON response includes the case metadata
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class CaseControllerMvcIntegrationSpec : SuperAdminCallerSpec() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var caseService: CaseService
    @Autowired lateinit var caseEventService: CaseEventService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var caseEventService: io.whozoss.agentos.caseEvent.CaseEventService
    @Autowired lateinit var caseService: CaseService
    @Autowired lateinit var permissionNodeRepository: PermissionNodeNeo4jRepository
    @Autowired lateinit var aiProviderService: AiProviderService
    @Autowired lateinit var aiModelService: AiModelService

    /** A model of [namespaceId] that [alias] resolves to, as the case-level model override needs. */
    private fun createModel(namespaceId: UUID, alias: String) {
        val provider = aiProviderService.create(
            AiProvider(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                namespaceId = namespaceId,
                name = "provider-${UUID.randomUUID()}",
                apiType = AiApiType.Anthropic,
            ),
        )
        aiModelService.create(
            AiModel(
                metadata = EntityMetadata(id = UUID.randomUUID()),
                aiProviderId = provider.id,
                namespaceId = namespaceId,
                apiModelName = "claude-$alias-test",
                alias = alias,
            ),
        )
    }

    init {

        "two collaborative MVC readers receive passive messages and unavailable invocation is denied without AI" {
            val ns = namespaceService.create(Namespace(name = "collaborative-two-readers"))
            val case = caseService.create(Case(namespaceId = ns.id, title = "Team smoke"))
            val readers = List(2) {
                mockMvc.perform(get("/api/cases/${case.id}/events")
                    .param("includePreviousEvents", "true")
                    .param("collaborative", "true"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                    .andReturn()
            }
            try {
                val runtime = caseService.getCaseRuntime(case.id)
                kotlinx.coroutines.withTimeout(5_000) {
                    while (runtime.subscriptionCount.value < 2) kotlinx.coroutines.delay(10)
                }
                listOf("first passive message", "second passive message").forEach { text ->
                    mockMvc.perform(post("/api/cases/${case.id}/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"content":"$text","mode":"HUMAN"}"""))
                        .andExpect(status().isOk)
                }
                kotlinx.coroutines.withTimeout(5_000) {
                    while (readers.any { !it.response.contentAsString.contains("second passive message") }) {
                        kotlinx.coroutines.delay(10)
                    }
                }
                readers.forEach { reader ->
                    val frames = reader.response.contentAsString
                    frames.contains("event:case-event") shouldBe true
                    frames.contains("first passive message") shouldBe true
                    frames.contains("\"startsAgentTurn\":false") shouldBe true
                    reader.request.isAsyncStarted shouldBe true
                }
                mockMvc.perform(post("/api/cases/${case.id}/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"content":"invoke","mode":"AGENT","agentName":"unavailable-smoke-agent"}"""))
                    .andExpect(status().isBadRequest)
                val stored = caseEventService.findByParent(case.id)
                stored.filterIsInstance<io.whozoss.agentos.sdk.caseEvent.MessageEvent>().size shouldBe 2
                stored.filterIsInstance<io.whozoss.agentos.sdk.caseEvent.MessageEvent>().all { !it.startsAgentTurn } shouldBe true
                stored.filterIsInstance<io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent>().size shouldBe 0
                caseService.getById(case.id).status shouldBe io.whozoss.agentos.sdk.caseFlow.CaseStatus.CREATED
            } finally {
                readers.forEach { it.request.asyncContext?.complete() }
            }
        }

        "HUMAN JSON request persists passive message in embedded Neo4j without AI" {
            val ns = namespaceService.create(Namespace(name = "collaborative-json"))
            val case = caseService.create(Case(namespaceId = ns.id, title = "Team discussion"))
            mockMvc.perform(post("/api/cases/${case.id}/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"@helper does not trigger AI","mode":"HUMAN"}"""))
                .andExpect(status().isOk)
            val events = caseEventService.findByParent(case.id)
            val message = events.filterIsInstance<io.whozoss.agentos.sdk.caseEvent.MessageEvent>().single()
            message.startsAgentTurn shouldBe false
            message.actor.id shouldBe userService.getCurrentUser().id.toString()
            events.filterIsInstance<io.whozoss.agentos.sdk.caseEvent.AgentSelectedEvent>().size shouldBe 0
            caseService.getById(case.id).status shouldBe io.whozoss.agentos.sdk.caseFlow.CaseStatus.CREATED
        }

        "invalid explicit mode payload is rejected through MVC before message persistence" {
            val ns = namespaceService.create(Namespace(name = "collaborative-invalid"))
            val case = caseService.create(Case(namespaceId = ns.id, title = "Team discussion"))
            listOf(
                """{"content":"hello","mode":"AGENT"}""",
                """{"content":"hello","mode":"HUMAN","agentName":"helper"}""",
                """{"content":"hello","mode":"UNKNOWN"}""",
            ).forEach { body ->
                mockMvc.perform(post("/api/cases/${case.id}/messages").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest)
            }
            caseEventService.findByParent(case.id).size shouldBe 0
        }

        "POST /api/cases with missing namespaceId returns 400" {
            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "title": "no namespace" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/cases with a valid namespaceId returns 201 (super-admin path)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-create"),
            )

            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "hello" }""")
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.namespaceId").value(ns.id.toString()))
                .andExpect(jsonPath("$.id").exists())
                // Title is now preserved after the CaseServiceImpl fix.
                .andExpect(jsonPath("$.title").value("hello"))
        }

        "POST /api/cases response body contains scheduledPromptId=null for a user-created case" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-sp-null"),
            )

            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            )
                .andExpect(status().isCreated)
                // scheduledPromptId is null for cases created directly by a user
                .andExpect(jsonPath("$.scheduledPromptId").doesNotExist())
        }

        "POST /api/cases with runCostThreshold preserves the value in the response" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-threshold"),
            )

            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "runCostThreshold": 25.0 }""")
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.runCostThreshold").value(25.0))
        }

        "POST /api/cases with negative runCostThreshold returns 400" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-threshold-neg"),
            )

            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "runCostThreshold": -1.0 }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/cases without title still returns 201 (title optional)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-no-title"),
            )

            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated)
        }

        // -------------------------------------------------------------------------
        // GET /api/cases/by-parentId/{namespaceId}
        // -------------------------------------------------------------------------

        "GET /api/cases/by-parentId/{namespaceId} returns cases for a super-admin caller" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-list-cases"),
            )
            // Create 2 cases in the namespace via the REST API (super-admin path)
            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated)
            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated)

            mockMvc.perform(get("/api/cases/by-parentId/${ns.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(2)))
        }

        // -------------------------------------------------------------------------
        // PUT /api/cases/{id}
        // -------------------------------------------------------------------------

        "PUT /api/cases/{id} updates title when caller has WRITE permission (super-admin)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-update"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "initial" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(
                put("/api/cases/$caseId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$caseId", "namespaceId": "${ns.id}", "title": "updated" }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(caseId))
                .andExpect(jsonPath("$.title").value("updated"))
                .andExpect(jsonPath("$.namespaceId").value(ns.id.toString()))
        }

        "PUT /api/cases/{id}/reasoning-effort sets and clears the override, which PUT /api/cases/{id} keeps" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-effort"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "initial", "reasoningEffort": "LOW" }""")
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.reasoningEffort").value("LOW"))
                .andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(
                put("/api/cases/$caseId/reasoning-effort")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "reasoningEffort": "XHIGH" }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.reasoningEffort").value("XHIGH"))

            mockMvc.perform(
                put("/api/cases/$caseId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$caseId", "namespaceId": "${ns.id}", "title": "renamed" }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.title").value("renamed"))
                .andExpect(jsonPath("$.reasoningEffort").value("XHIGH"))

            mockMvc.perform(
                put("/api/cases/$caseId/reasoning-effort")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "reasoningEffort": null }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.reasoningEffort").doesNotExist())
        }

        "PUT /api/cases/{id}/reasoning-effort with an unknown level returns 400" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-effort-400"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(
                put("/api/cases/$caseId/reasoning-effort")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "reasoningEffort": "minimal" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/cases/{id}/model sets and clears the override, which PUT /api/cases/{id} keeps" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-model"),
            )
            createModel(ns.id, "BIG")
            createModel(ns.id, "SMALL")
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "initial", "modelName": "SMALL" }""")
            )
                .andExpect(status().isCreated)
                .andExpect(jsonPath("$.modelName").value("SMALL"))
                .andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(
                put("/api/cases/$caseId/model")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "modelName": "BIG" }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.modelName").value("BIG"))

            mockMvc.perform(
                put("/api/cases/$caseId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$caseId", "namespaceId": "${ns.id}", "title": "renamed" }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.title").value("renamed"))
                .andExpect(jsonPath("$.modelName").value("BIG"))

            mockMvc.perform(
                put("/api/cases/$caseId/model")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "modelName": null }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.modelName").doesNotExist())
        }

        "PUT /api/cases/{id}/model with an unknown model returns 400 and keeps the current override" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-model-400"),
            )
            createModel(ns.id, "BIG")
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "modelName": "BIG" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(
                put("/api/cases/$caseId/model")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "modelName": "no-such-model" }""")
            ).andExpect(status().isBadRequest)

            mockMvc.perform(get("/api/cases/$caseId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.modelName").value("BIG"))
        }

        "POST /api/cases with an unknown model returns 400" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-create-model-400"),
            )

            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "modelName": "no-such-model" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/cases/{id}/model returns 404 when the case does not exist" {
            mockMvc.perform(
                put("/api/cases/${UUID.randomUUID()}/model")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "modelName": null }""")
            ).andExpect(status().isNotFound)
        }

        "PUT /api/cases/{id} returns 404 when case does not exist" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-put-404"),
            )
            val missing = UUID.randomUUID()

            mockMvc.perform(
                put("/api/cases/$missing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$missing", "namespaceId": "${ns.id}", "title": "nope" }""")
            ).andExpect(status().isNotFound)
        }

        "PUT /api/cases/{id} with missing namespaceId returns 400 (Bean Validation)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-put-400"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(
                put("/api/cases/$caseId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$caseId", "title": "no namespace" }""")
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // DELETE /api/cases/{id}
        // -------------------------------------------------------------------------

        "DELETE /api/cases/{id} returns 204 and makes the case invisible in subsequent listings" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-delete"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "to-delete" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(delete("/api/cases/$caseId"))
                .andExpect(status().isNoContent)

            // The deleted case should no longer appear in listings
            mockMvc.perform(get("/api/cases/by-parentId/${ns.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.id == '$caseId')]", org.hamcrest.Matchers.hasSize<Any>(0)))
        }

        // -------------------------------------------------------------------------
        // GET /api/cases/by-user/{userId}
        // -------------------------------------------------------------------------

        "GET /api/cases/by-user/{userId} returns cases across multiple namespaces for that user" {
            val ns1 = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "user-id-ns1"),
            )
            val ns2 = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "user-id-ns2"),
            )
            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns1.id}", "title": "case in ns1" }""")
            ).andExpect(status().isCreated)
            mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns2.id}", "title": "case in ns2" }""")
            ).andExpect(status().isCreated)

            val userId = userService.getCurrentUser().id

            mockMvc.perform(get("/api/cases/by-user/$userId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize<Any>(org.hamcrest.Matchers.greaterThanOrEqualTo(2))))
        }

        "GET /api/cases/by-user/{userId} returns 200 with empty list when user has no cases" {
            val userId = userService.getCurrentUser().id
            mockMvc.perform(get("/api/cases/by-user/$userId"))
                .andExpect(status().isOk)
        }

        // -------------------------------------------------------------------------
        // POST /api/cases/{caseId}/rewind
        // -------------------------------------------------------------------------

        "POST /api/cases/{id}/rewind returns 200 with status IDLE and removes the message and what follows" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-rewind"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = UUID.fromString(Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1])
            val actor = Actor(id = UUID.randomUUID().toString(), displayName = "User", role = ActorRole.USER)
            val message = caseEventService.create(
                MessageEvent(namespaceId = ns.id, caseId = caseId, actor = actor, content = listOf(MessageContent.Text("hi"))),
            )
            caseEventService.create(WarnEvent(namespaceId = ns.id, caseId = caseId, message = "after"))

            mockMvc.perform(
                post("/api/cases/$caseId/rewind")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "eventId": "${message.id}" }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(caseId.toString()))
                .andExpect(jsonPath("$.status").value("IDLE"))

            caseEventService.findByParent(caseId).none { it is MessageEvent || it is WarnEvent } shouldBe true
            caseEventService.findByParent(caseId).last().shouldBeInstanceOf<CaseStatusEvent>().status shouldBe CaseStatus.IDLE
        }

        "POST /api/cases/{id}/rewind returns 409 on a KILLED case" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-rewind-killed"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = UUID.fromString(Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1])
            val actor = Actor(id = UUID.randomUUID().toString(), displayName = "User", role = ActorRole.USER)
            val message = caseEventService.create(
                MessageEvent(namespaceId = ns.id, caseId = caseId, actor = actor, content = listOf(MessageContent.Text("hi"))),
            )
            caseService.killCase(caseId)

            mockMvc.perform(
                post("/api/cases/$caseId/rewind")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "eventId": "${message.id}" }""")
            ).andExpect(status().isConflict)
        }

        "POST /api/cases/{id}/rewind returns 404 for an unknown event and 400 for a non-user-message event" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-rewind-errors"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = UUID.fromString(Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1])
            val warn = caseEventService.create(WarnEvent(namespaceId = ns.id, caseId = caseId, message = "warn"))

            mockMvc.perform(
                post("/api/cases/$caseId/rewind")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "eventId": "${UUID.randomUUID()}" }""")
            ).andExpect(status().isNotFound)
            mockMvc.perform(
                post("/api/cases/$caseId/rewind")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "eventId": "${warn.id}" }""")
            ).andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // PUT /api/cases/{id}/star and DELETE /api/cases/{id}/star
        // -------------------------------------------------------------------------

        "PUT /api/cases/{id}/star returns 200 when the caller has a direct permission edge" {
            // POST /api/cases auto-grants [:ADMIN] on the case via permissionService.grantPermission
            // (PermissionServiceImpl is active in embedded-neo4j profile). No manual setup needed.
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "star-ok-ns"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            // starCase is @ResponseStatus(OK) — returns 200, not 204.
            mockMvc.perform(put("/api/cases/$caseId/star"))
                .andExpect(status().isOk)
        }

        "PUT /api/cases/{id}/star returns 409 when the caller has no direct permission edge" {
            // POST /api/cases auto-grants [:ADMIN] on the new case via permissionService.grantPermission.
            // To test the 409 path we must revoke that edge after creation.
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "star-409-ns"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            // Revoke the auto-granted [:ADMIN] edge so mergeStarred's MATCH finds nothing → returns 0 → 409.
            val caller = userService.getCurrentUser()
            permissionNodeRepository.deleteAdminPermission(
                userId = caller.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
            )

            mockMvc.perform(put("/api/cases/$caseId/star"))
                .andExpect(status().isConflict)
        }

        "DELETE /api/cases/{id}/star returns 204 when the caller has a direct permission edge" {
            // POST auto-grants [:ADMIN]; star (200) then unstar (204).
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "unstar-ok-ns"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(put("/api/cases/$caseId/star")).andExpect(status().isOk)
            mockMvc.perform(delete("/api/cases/$caseId/star")).andExpect(status().isNoContent)
        }

        "DELETE /api/cases/{id}/star returns 409 when the caller has no direct permission edge" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "unstar-409-ns"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            // Revoke the auto-granted [:ADMIN] edge so deleteStarred's MATCH finds nothing → returns 0 → 409.
            val caller = userService.getCurrentUser()
            permissionNodeRepository.deleteAdminPermission(
                userId = caller.id.toString(),
                entityId = caseId,
                entityLabel = "Case",
            )

            mockMvc.perform(delete("/api/cases/$caseId/star"))
                .andExpect(status().isConflict)
        }

        "DELETE /api/cases/{id} returns 404 on a second attempt (already soft-deleted)" {
            val ns = namespaceService.create(
                Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "team-case-delete-twice"),
            )
            val createResponse = mockMvc.perform(
                post("/api/cases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]+)\"").find(createResponse)!!.groupValues[1]

            mockMvc.perform(delete("/api/cases/$caseId")).andExpect(status().isNoContent)
            mockMvc.perform(delete("/api/cases/$caseId")).andExpect(status().isNotFound)
        }
    }
}
