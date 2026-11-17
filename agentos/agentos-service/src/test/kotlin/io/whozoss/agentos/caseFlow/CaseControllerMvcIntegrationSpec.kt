package io.whozoss.agentos.caseFlow

import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.permissions.PermissionNodeNeo4jRepository
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
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
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = ["agentos.case.naming-sweep-ms=3600000"],
)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class CaseControllerMvcIntegrationSpec : SuperAdminCallerSpec() {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: com.fasterxml.jackson.databind.ObjectMapper
    @Autowired lateinit var caseRepository: CaseRepository
    @Autowired lateinit var namespaceService: NamespaceService
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var permissionNodeRepository: PermissionNodeNeo4jRepository

    init {

        "OpenAPI exposes titleChange as a write-only optional boolean" {
            //given / when
            val response = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk).andReturn().response.contentAsString
            val specification = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(response)
            val field = specification.path("components").path("schemas").path("Case").path("properties").path("titleChange")
            //then
            check(field.path("type").map { it.asText() }.toSet() == setOf("boolean", "null"))
            check(field.path("writeOnly").asBoolean())
            check(field.path("description").asText().contains("false ignores title"))
            val committed = com.fasterxml.jackson.databind.ObjectMapper(com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readTree(java.io.File("../openapi/agentos-openapi.yaml"))
            val committedCase = committed.path("components").path("schemas").path("Case")
            val committedField = committedCase.path("properties").path("titleChange")
            check(committedField == field)
            check(committedCase.path("required").none { it.asText() == "titleChange" })
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

        "POST and GET case responses never expose pending naming instructions" {
            val ns = namespaceService.create(Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "private-naming"))
            val id = UUID.randomUUID()
            val body = """{"id":"$id","namespaceId":"${ns.id}","title":"WZ-42","namingInstructions":"private-request-482","namingPrefix":"WZ-42"}"""
            val created = mockMvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated).andReturn().response.contentAsString
            val fetched = mockMvc.perform(get("/api/cases/$id")).andExpect(status().isOk).andReturn().response.contentAsString
            check(!created.contains("private-request-482"))
            check(!fetched.contains("private-request-482"))
        }

        "POST /api/cases completes while generation is blocked and late result cannot overwrite a manual rename" {
            //given
            val ns = namespaceService.create(Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "naming-blocked-http"))
            val firstId = UUID.randomUUID()
            mockMvc.perform(
                post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"id":"$firstId","namespaceId":"${ns.id}","title":"WZ-42","namingInstructions":"Investigate login","namingPrefix":"WZ-42"}""")
            ).andExpect(status().isCreated)

            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val completed = java.util.concurrent.CountDownLatch(1)
            val completionResult = java.util.concurrent.atomic.AtomicReference<Case?>()
            val providerId = UUID.randomUUID()
            val model = io.whozoss.agentos.sdk.aiProvider.AiModel(
                metadata = EntityMetadata(), aiProviderId = providerId, namespaceId = ns.id, apiModelName = "test-model",
            )
            val provider = io.whozoss.agentos.sdk.aiProvider.AiProvider(
                metadata = EntityMetadata(id = providerId), namespaceId = ns.id,
                name = "test-provider", apiType = io.whozoss.agentos.sdk.aiProvider.AiApiType.OpenAI,
            )
            val chat = mockk<org.springframework.ai.chat.client.ChatClient>()
            val request = mockk<org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec>()
            val response = mockk<org.springframework.ai.chat.client.ChatClient.CallResponseSpec>()
            every { chat.prompt(any<org.springframework.ai.chat.prompt.Prompt>()) } returns request
            every { request.call() } returns response
            every { response.content() } answers {
                entered.countDown()
                check(release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                "Late generated title"
            }
            val repository = object : CaseRepository by caseRepository {
                override fun findPendingNaming(limit: Int): List<Case> =
                    caseRepository.findPendingNaming(limit).filter { it.id == firstId }

                override fun completeNaming(candidate: Case, title: String): Case? {
                    val result = caseRepository.completeNaming(candidate, title)
                    if (candidate.id == firstId) {
                        completionResult.set(result)
                        completed.countDown()
                    }
                    return result
                }
            }
            val naming = CaseNamingService(
                aiModelService = mockk { every { findAiModel(any(), any()) } returns model },
                aiProviderService = mockk { every { getById(any()) } returns provider },
                chatClientProvider = mockk { every { getChatClient(any(), any(), any(), any(), any()) } returns chat },
                caseRepository = repository,
                namingCallTimeoutMs = 20_000,
            )
            val httpExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
            try {
                naming.sweepPendingNaming()
                check(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Generation never started" }

                //when: another root case is created while the first generation remains blocked
                val secondId = UUID.randomUUID()
                val create = httpExecutor.submit<Int> {
                    mockMvc.perform(
                        post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                            .content("""{"id":"$secondId","namespaceId":"${ns.id}","namingInstructions":"Second request"}""")
                    ).andReturn().response.status
                }
                check(create.get(10, java.util.concurrent.TimeUnit.SECONDS) == 201)
                check(release.count == 1L)
                mockMvc.perform(
                    put("/api/cases/$firstId").contentType(MediaType.APPLICATION_JSON)
                        .content("""{"namespaceId":"${ns.id}","title":"Manual title","titleChange":true}""")
                ).andExpect(status().isOk).andExpect(jsonPath("$.title").value("Manual title"))
                release.countDown()

                //then: the real repository rejects the late generated result
                check(completed.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Late completion not observed" }
                check(completionResult.get() == null)
                val persisted = caseRepository.findByIds(listOf(firstId)).single()
                check(persisted.title == "Manual title")
                check(persisted.namingInstructions == null)
                check(persisted.titleEditGeneration != null)
            } finally {
                release.countDown()
                httpExecutor.shutdownNow()
                naming.shutdown()
            }
        }

        "a reconstructed naming service resumes an expired persisted claim" {
            //given: persisted work and expired lease survive disposal of the first service
            val ns = namespaceService.create(Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "naming-restart"))
            val id = UUID.randomUUID()
            mockMvc.perform(
                post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                    .content("""{"id":"$id","namespaceId":"${ns.id}","title":"WZ-42","namingInstructions":"Investigate restart","namingPrefix":"WZ-42"}""")
            ).andExpect(status().isCreated)
            val pending = caseRepository.findByIds(listOf(id)).single()
            val now = java.time.Instant.now()
            check(caseRepository.claimNaming(pending, "previous-process", now.minusSeconds(60), now.minusSeconds(1)) != null)
            val modelService = mockk<io.whozoss.agentos.aiModel.AiModelService> {
                every { findAiModel(any(), any()) } returns null
            }
            val first = CaseNamingService(modelService, mockk(), mockk(), caseRepository)
            first.shutdown()
            val completed = java.util.concurrent.CountDownLatch(1)
            val repository = object : CaseRepository by caseRepository {
                override fun findPendingNaming(limit: Int): List<Case> =
                    caseRepository.findPendingNaming(limit).filter { it.id == id }

                override fun completeNaming(candidate: Case, title: String): Case? {
                    val result = caseRepository.completeNaming(candidate, title)
                    if (candidate.id == id) completed.countDown()
                    return result
                }
            }
            val reconstructed = CaseNamingService(modelService, mockk(), mockk(), repository)
            try {
                //when
                reconstructed.sweepPendingNaming()

                //then
                check(completed.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "Persisted intent was not resumed" }
                val stored = caseRepository.findByIds(listOf(id)).single()
                check(stored.title == "WZ-42 Investigate restart")
                check(stored.namingInstructions == null && stored.namingLeaseOwner == null && stored.namingLeaseUntil == null)
            } finally {
                reconstructed.shutdown()
            }
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

            val updated = mockMvc.perform(
                put("/api/cases/$caseId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$caseId", "namespaceId": "${ns.id}", "title": "updated", "titleChange": true }""")
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(caseId))
                .andExpect(jsonPath("$.title").value("updated"))
                .andExpect(jsonPath("$.namespaceId").value(ns.id.toString()))
                .andReturn().response.contentAsString
            check(!com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().readTree(updated).has("titleChange"))

            mockMvc.perform(
                put("/api/cases/$caseId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "stale", "titleChange": false }""")
            ).andExpect(status().isOk).andExpect(jsonPath("$.title").value("updated"))

            mockMvc.perform(
                put("/api/cases/$caseId")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "legacy", "titleChange": null }""")
            ).andExpect(status().isOk).andExpect(jsonPath("$.title").value("legacy"))
        }

        "SDK case DTO serializes false title intent for PUT without overwriting the title" {
            //given
            val ns = namespaceService.create(Namespace(metadata = EntityMetadata(id = UUID.randomUUID()), name = "sdk-title-intent"))
            val created = mockMvc.perform(
                post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "${ns.id}", "title": "current" }""")
            ).andExpect(status().isCreated).andReturn().response.contentAsString
            val caseId = UUID.fromString(objectMapper.readTree(created).path("id").asText())
            val request = io.whozoss.agentos.sdk.api.case.CaseDto(
                namespaceId = ns.id, title = "stale", titleChange = false,
            )

            //when
            val requestJson = objectMapper.writeValueAsString(request)

            //then
            check(objectMapper.readTree(requestJson).path("titleChange").isBoolean)
            mockMvc.perform(put("/api/cases/$caseId").contentType(MediaType.APPLICATION_JSON).content(requestJson))
                .andExpect(status().isOk).andExpect(jsonPath("$.title").value("current"))
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
        // DELETE /api/cases/{id} returns 404 on a second attempt (already soft-deleted)
        // -------------------------------------------------------------------------

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
