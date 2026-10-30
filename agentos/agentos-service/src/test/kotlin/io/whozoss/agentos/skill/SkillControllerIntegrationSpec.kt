package io.whozoss.agentos.skill

import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.security.SuperAdminCallerSpec
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID
import kotlin.io.path.writeText

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class SkillControllerIntegrationSpec : SuperAdminCallerSpec() {

    @Autowired lateinit var mockMvc: MockMvc

    @Autowired lateinit var skillService: SkillService

    @Autowired lateinit var namespaceService: NamespaceService

    private val namespaceId = UUID.randomUUID()

    init {
        // -------------------------------------------------------------------------
        // POST /api/skills — create
        // -------------------------------------------------------------------------

        "POST /api/skills with blank name returns 400" {
            mockMvc
                .perform(
                    post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "", "description": "desc", "body": "body" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/skills with blank description returns 400" {
            mockMvc
                .perform(
                    post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "skill", "description": "", "body": "body" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/skills with blank body returns 400" {
            mockMvc
                .perform(
                    post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "namespaceId": "$namespaceId", "name": "skill", "description": "desc", "body": "" }"""),
                ).andExpect(status().isBadRequest)
        }

        "POST /api/skills with valid payload returns 201" {
            val name = "skill-${UUID.randomUUID()}"
            mockMvc
                .perform(
                    post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "namespaceId": "$namespaceId",
                                "name": "$name",
                                "description": "A test skill",
                                "body": "## Instructions\nFollow guidelines."
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.description").value("A test skill"))
                .andExpect(jsonPath("$.body").value("## Instructions\nFollow guidelines."))
        }

        "POST /api/skills with duplicate name in same namespace returns 409" {
            val name = "dup-skill-${UUID.randomUUID()}"
            val payload = """
                {
                    "namespaceId": "$namespaceId",
                    "name": "$name",
                    "description": "desc",
                    "body": "body"
                }
            """.trimIndent()

            mockMvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated)

            mockMvc.perform(post("/api/skills").contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isConflict)
        }

        // -------------------------------------------------------------------------
        // PUT /api/skills/{id} — update
        // -------------------------------------------------------------------------

        "PUT /api/skills/{id} with blank name returns 400" {
            val id = UUID.randomUUID()
            mockMvc
                .perform(
                    put("/api/skills/$id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "id": "$id", "namespaceId": "$namespaceId", "name": "", "description": "desc", "body": "body" }"""),
                ).andExpect(status().isBadRequest)
        }

        "PUT /api/skills/{id} with valid payload returns 200" {
            val created =
                skillService.create(
                    Skill(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "initial-skill-${UUID.randomUUID()}",
                        description = "initial desc",
                        body = "initial body",
                    ),
                )

            val newName = "updated-skill-${UUID.randomUUID()}"
            mockMvc
                .perform(
                    put("/api/skills/${created.id}")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "id": "${created.id}",
                                "namespaceId": "$namespaceId",
                                "name": "$newName",
                                "description": "updated desc",
                                "body": "updated body"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(created.id.toString()))
                .andExpect(jsonPath("$.name").value(newName))
                .andExpect(jsonPath("$.description").value("updated desc"))
                .andExpect(jsonPath("$.body").value("updated body"))
        }

        "PUT /api/skills/{id} on unknown id returns 404" {
            val unknownId = UUID.randomUUID()
            mockMvc
                .perform(
                    put("/api/skills/$unknownId")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "id": "$unknownId",
                                "namespaceId": "$namespaceId",
                                "name": "some-name",
                                "description": "desc",
                                "body": "body"
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // GET /api/skills/platform
        // -------------------------------------------------------------------------

        "GET /api/skills/platform returns platform-level skills" {
            val platformName = "platform-skill-${UUID.randomUUID()}"
            skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = platformName,
                    description = "Platform desc",
                    body = "Platform body",
                ),
            )

            mockMvc
                .perform(get("/api/skills/platform"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '$platformName')]", org.hamcrest.Matchers.hasSize<Any>(1)))
        }

        // -------------------------------------------------------------------------
        // GET /api/skills/by-parentId/{namespaceId}
        // -------------------------------------------------------------------------

        "GET /api/skills/by-parentId/{namespaceId} returns skills for namespace" {
            val listNsId = UUID.randomUUID()
            namespaceService.create(Namespace(metadata = EntityMetadata(id = listNsId), name = "ns-$listNsId"))

            val nameA = "ns-skill-a-${UUID.randomUUID()}"
            val nameB = "ns-skill-b-${UUID.randomUUID()}"

            skillService.create(
                Skill(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = listNsId, name = nameA, description = "D", body = "B"),
            )
            skillService.create(
                Skill(metadata = EntityMetadata(id = UUID.randomUUID()), namespaceId = listNsId, name = nameB, description = "D", body = "B"),
            )

            mockMvc
                .perform(get("/api/skills/by-parentId/$listNsId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '$nameA')]", org.hamcrest.Matchers.hasSize<Any>(1)))
                .andExpect(jsonPath("$[?(@.name == '$nameB')]", org.hamcrest.Matchers.hasSize<Any>(1)))
        }

        // -------------------------------------------------------------------------
        // GET /api/skills/{id} & DELETE /api/skills/{id}
        // -------------------------------------------------------------------------

        "GET /api/skills/{id} returns 200 with payload" {
            val created =
                skillService.create(
                    Skill(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "get-skill-${UUID.randomUUID()}",
                        description = "get desc",
                        body = "get body",
                    ),
                )

            mockMvc
                .perform(get("/api/skills/${created.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(created.id.toString()))
                .andExpect(jsonPath("$.name").value(created.name))
        }

        "DELETE /api/skills/{id} returns 204" {
            val created =
                skillService.create(
                    Skill(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "delete-skill-${UUID.randomUUID()}",
                        description = "del desc",
                        body = "del body",
                    ),
                )

            mockMvc
                .perform(delete("/api/skills/${created.id}"))
                .andExpect(status().isNoContent)
        }

        // -------------------------------------------------------------------------
        // readOnly — storage origin exposed on the DTO
        // -------------------------------------------------------------------------

        "POST then GET report readOnly false for a persisted skill" {
            val created =
                skillService.create(
                    Skill(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = namespaceId,
                        name = "persisted-readonly-${UUID.randomUUID()}",
                        description = "D",
                        body = "B",
                    ),
                )

            mockMvc
                .perform(get("/api/skills/${created.id}"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.readOnly").value(false))
        }

        "POST ignores a client-supplied readOnly, like the audit fields" {
            val name = "ignored-readonly-${UUID.randomUUID()}"
            mockMvc
                .perform(
                    post("/api/skills")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                                "namespaceId": "$namespaceId",
                                "name": "$name",
                                "description": "D",
                                "body": "B",
                                "readOnly": true
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andExpect(jsonPath("$.readOnly").value(false))
        }

        "GET /api/skills/by-parentId reports readOnly true for the filesystem entry and false for the persisted one" {
            val configRoot = java.nio.file.Files.createTempDirectory("skill-readonly-flag-test")
            val skillDir = configRoot.resolve("skills").resolve("fs-flagged")
            java.nio.file.Files.createDirectories(skillDir)
            skillDir.resolve("SKILL.md").writeText(
                """
                ---
                name: fs-flagged
                description: Filesystem-backed flagged skill
                ---
                Body content
                """.trimIndent(),
            )

            val mixedNsId = UUID.randomUUID()
            namespaceService.create(
                Namespace(
                    metadata = EntityMetadata(id = mixedNsId),
                    name = "ns-mixed-$mixedNsId",
                    configPath = configRoot.toString(),
                ),
            )

            val persistedName = "db-flagged-${UUID.randomUUID()}"
            skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = mixedNsId,
                    name = persistedName,
                    description = "D",
                    body = "B",
                ),
            )

            mockMvc
                .perform(get("/api/skills/by-parentId/$mixedNsId"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == 'fs-flagged' && @.readOnly == true)]", org.hamcrest.Matchers.hasSize<Any>(1)))
                .andExpect(jsonPath("$[?(@.name == '$persistedName' && @.readOnly == false)]", org.hamcrest.Matchers.hasSize<Any>(1)))
        }

        // -------------------------------------------------------------------------
        // GET /api/skills/effective
        // -------------------------------------------------------------------------

        "GET /api/skills/effective merges namespace, filesystem and platform skills as summaries" {
            val configRoot = java.nio.file.Files.createTempDirectory("skill-effective-test")
            val skillDir = configRoot.resolve("skills").resolve("fs-effective")
            java.nio.file.Files.createDirectories(skillDir)
            skillDir.resolve("SKILL.md").writeText(
                """
                ---
                name: fs-effective
                description: Filesystem-backed effective skill
                ---
                Filesystem body
                """.trimIndent(),
            )

            val effectiveNsId = UUID.randomUUID()
            namespaceService.create(
                Namespace(
                    metadata = EntityMetadata(id = effectiveNsId),
                    name = "ns-effective-$effectiveNsId",
                    configPath = configRoot.toString(),
                ),
            )

            val nsName = "effective-ns-${UUID.randomUUID()}"
            skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = effectiveNsId,
                    name = nsName,
                    description = "D",
                    body = "NS body",
                ),
            )
            val platformName = "effective-platform-${UUID.randomUUID()}"
            skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = platformName,
                    description = "D",
                    body = "Platform body",
                ),
            )

            performEffective(effectiveNsId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '$nsName')]", org.hamcrest.Matchers.hasSize<Any>(1)))
                .andExpect(jsonPath("$[?(@.name == '$platformName')]", org.hamcrest.Matchers.hasSize<Any>(1)))
                .andExpect(jsonPath("$[?(@.name == 'fs-effective' && @.readOnly == true)]", org.hamcrest.Matchers.hasSize<Any>(1)))
                // Summary projection: bodies and resources never travel in this listing.
                .andExpect(jsonPath("$[0].body").doesNotExist())
                .andExpect(jsonPath("$[0].resources").doesNotExist())
        }

        "GET /api/skills/effective applies namespace-over-platform shadowing, returning one entry" {
            val shadowNsId = UUID.randomUUID()
            namespaceService.create(Namespace(metadata = EntityMetadata(id = shadowNsId), name = "ns-shadow-$shadowNsId"))

            val sharedName = "shadowed-skill-${UUID.randomUUID()}"
            skillService.create(
                Skill(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = null,
                    name = sharedName,
                    description = "platform version",
                    body = "B",
                ),
            )
            val namespaceSkill =
                skillService.create(
                    Skill(
                        metadata = EntityMetadata(id = UUID.randomUUID()),
                        namespaceId = shadowNsId,
                        name = sharedName,
                        description = "namespace version",
                        body = "B",
                    ),
                )

            performEffective(shadowNsId)
                .andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '$sharedName')]", org.hamcrest.Matchers.hasSize<Any>(1)))
                .andExpect(jsonPath("$[?(@.id == '${namespaceSkill.id}')]", org.hamcrest.Matchers.hasSize<Any>(1)))
                .andExpect(jsonPath("$[?(@.description == 'namespace version')]", org.hamcrest.Matchers.hasSize<Any>(1)))
        }

        // -------------------------------------------------------------------------
        // POST /api/skills/by-ids
        // -------------------------------------------------------------------------

        "POST /api/skills/by-ids returns matching entities" {
            val a = skillService.create(Skill(name = "byid-a-${UUID.randomUUID()}", description = "D", body = "B", namespaceId = namespaceId))
            val b = skillService.create(Skill(name = "byid-b-${UUID.randomUUID()}", description = "D", body = "B", namespaceId = namespaceId))

            mockMvc
                .perform(
                    post("/api/skills/by-ids")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{ "ids": ["${a.id}", "${b.id}"] }"""),
                ).andExpect(status().isOk)
                .andExpect(jsonPath("$[?(@.name == '${a.name}')]", org.hamcrest.Matchers.hasSize<Any>(1)))
                .andExpect(jsonPath("$[?(@.name == '${b.name}')]", org.hamcrest.Matchers.hasSize<Any>(1)))
        }
    }
    /**
     * `GET /api/skills/effective` is a `suspend` handler, so Spring MVC completes it through the
     * async dispatch: the initial `perform` only starts it and carries no body. Every assertion
     * must therefore be made on the dispatched result.
     */
    private fun performEffective(namespaceId: UUID): ResultActions {
        val started =
            mockMvc
                .perform(get("/api/skills/effective").param("namespaceId", namespaceId.toString()))
                .andExpect(request().asyncStarted())
                .andReturn()
        return mockMvc.perform(asyncDispatch(started))
    }
}
