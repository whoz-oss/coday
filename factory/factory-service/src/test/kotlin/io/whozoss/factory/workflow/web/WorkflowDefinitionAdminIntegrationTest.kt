package io.whozoss.factory.workflow.web

import io.whozoss.factory.Neo4jDomainIntegrationTest
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.web.FakeIdp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.core.ParameterizedTypeReference
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.LinkedMultiValueMap

/**
 * HTTP integration tests of the workflow-definition registry, against a real
 * servlet container and the embedded Neo4j engine.
 *
 * They pin the exact contracts:
 *   - any authenticated user can register definitions (`POST`), upload JSON files
 *     (`POST /upload`), list/get them and delete one by `(type, version)`;
 *   - invalid JSON and non-conforming definitions are rejected with a clean
 *     `400` and a readable machine code.
 */
class WorkflowDefinitionAdminIntegrationTest : Neo4jDomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var factoryProperties: FactoryProperties

    private val adminToken: String
        get() = FakeIdp.issueJwt(
            mapOf("principalId" to "admin-user"),
            factoryProperties.security.fakeIdpSecret,
        )

    private fun validDefinition(workflowType: String = "wf-admin", version: String = "1.0.0"): Map<String, Any?> =
        linkedMapOf(
            "schemaVersion" to "1",
            "workflowType" to workflowType,
            "version" to version,
            "title" to "Admin workflow",
            "steps" to listOf(
                linkedMapOf(
                    "id" to "gate",
                    "name" to "Gate",
                    "responsibility" to linkedMapOf("kind" to "human", "name" to "reviewer"),
                    "dependsOn" to emptyList<String>(),
                ),
            ),
        )

    private fun jsonHeaders(token: String): HttpHeaders = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        setBearerAuth(token)
    }

    private fun authHeaders(token: String): HttpHeaders = HttpHeaders().apply { setBearerAuth(token) }

    private fun postJson(path: String, token: String, body: Any?): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, jsonHeaders(token)), mapType())

    private fun postMultipart(path: String, token: String, fileName: String, content: String): ResponseEntity<Map<String, Any?>> {
        val resource = object : ByteArrayResource(content.toByteArray(Charsets.UTF_8)) {
            override fun getFilename(): String = fileName
        }
        val body = LinkedMultiValueMap<String, Any>().apply { add("file", resource) }
        val headers = HttpHeaders().apply {
            contentType = MediaType.MULTIPART_FORM_DATA
            setBearerAuth(token)
        }
        return restTemplate.exchange(path, HttpMethod.POST, HttpEntity(body, headers), mapType())
    }

    private fun get(path: String, token: String): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(authHeaders(token)), mapType())

    private fun delete(path: String, token: String): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(path, HttpMethod.DELETE, HttpEntity<Void>(authHeaders(token)), mapType())

    private fun mapType(): ParameterizedTypeReference<Map<String, Any?>> =
        object : ParameterizedTypeReference<Map<String, Any?>>() {}

    @Suppress("UNCHECKED_CAST")
    private fun data(response: ResponseEntity<Map<String, Any?>>): Map<String, Any?> =
        response.body?.get("data") as? Map<String, Any?> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private fun errorCode(response: ResponseEntity<Map<String, Any?>>): String? =
        (response.body?.get("error") as? Map<String, Any?>)?.get("code") as? String

    @Suppress("UNCHECKED_CAST")
    private fun listItems(response: ResponseEntity<Map<String, Any?>>): List<Map<String, Any?>> =
        (data(response)["items"] as? List<*>)?.filterIsInstance<Map<String, Any?>>() ?: emptyList()

    @Test
    fun `validation uses registration hash without persisting a definition or creating a run`() {
        val definition = validDefinition("wf-dry-run")
        val validated = postJson("$REGISTER_PATH/validate", adminToken, definition)
        assertThat(validated.statusCode.value()).isEqualTo(200)
        assertThat(data(validated)["valid"]).isEqualTo(true)
        assertThat(get("$REGISTER_PATH/wf-dry-run/1.0.0", adminToken).statusCode.value()).isEqualTo(404)
        neo4jDriver.session().use { session ->
            assertThat(session.run("MATCH (n:WorkflowInstance) RETURN count(n) AS count").single()["count"].asLong()).isZero()
        }
        val registered = postJson(REGISTER_PATH, adminToken, definition)
        assertThat(data(registered)["definitionHash"]).isEqualTo(data(validated)["definitionHash"])
    }

    @Test
    fun `validation and registration preserve mixed agent human and legacy steps`() {
        val original = validDefinition("wf-mixed-authoring")
        @Suppress("UNCHECKED_CAST")
        val human = (original["steps"] as List<Map<String, Any?>>).single()
        val agent = mapOf(
            "id" to "analyze", "name" to "Analyze",
            "responsibility" to mapOf("kind" to "agent", "name" to "Searchay"),
            "dependsOn" to emptyList<String>(), "entry" to emptyList<String>(),
            "mission" to "Analyze the requested scope", "exit" to listOf("Provide findings"),
        )
        val definition = original + ("steps" to listOf(agent, human + ("dependsOn" to listOf("analyze"))))
        val validated = postJson("$REGISTER_PATH/validate", adminToken, definition)
        val registered = postJson(REGISTER_PATH, adminToken, definition)
        assertThat(validated.statusCode.value()).isEqualTo(200)
        assertThat(registered.statusCode.value()).isEqualTo(201)
        assertThat(data(validated)["definitionHash"]).isEqualTo(data(registered)["definitionHash"])
        assertThat(data(get("$REGISTER_PATH/wf-mixed-authoring/1.0.0", adminToken))["definition"]).isEqualTo(definition)
        neo4jDriver.session().use { session ->
            assertThat(session.run("MATCH (n:WorkflowInstance) RETURN count(n) AS count").single()["count"].asLong()).isZero()
        }
    }

    @Test
    fun `validation and registration report identical partial contract and graph errors`() {
        val base = validDefinition("wf-invalid-contract")
        @Suppress("UNCHECKED_CAST")
        val step = (base["steps"] as List<Map<String, Any?>>).single()
        val invalidDefinitions = listOf(
            base + ("steps" to listOf(step + ("mission" to "Missing entry and exit"))),
            base + ("steps" to listOf(step + ("dependsOn" to listOf("absent")))),
            base + ("steps" to listOf(step + ("dependsOn" to listOf("gate")))),
        )
        invalidDefinitions.forEach { definition ->
            val validated = postJson("$REGISTER_PATH/validate", adminToken, definition)
            val registered = postJson(REGISTER_PATH, adminToken, definition)
            assertThat(validated.statusCode.value()).isEqualTo(400)
            assertThat(validated.body?.get("error")).isEqualTo(registered.body?.get("error"))
            assertThat(validated.body.toString()).contains("path")
        }
    }

    @Test
    fun `admin registers a definition and it is listed and readable`() {
        val register = postJson(REGISTER_PATH, adminToken, validDefinition())
        assertThat(register.statusCode.value()).isEqualTo(201)
        val registered = data(register)
        assertThat(registered["workflowType"]).isEqualTo("wf-admin")
        assertThat(registered["version"]).isEqualTo("1.0.0")
        assertThat(registered["definitionHash"] as String).hasSize(64)

        val list = get(REGISTER_PATH, adminToken)
        assertThat(list.statusCode.value()).isEqualTo(200)
        assertThat(listItems(list).map { it["workflowType"] }).contains("wf-admin")

        val detail = get("$REGISTER_PATH/wf-admin/1.0.0", adminToken)
        assertThat(detail.statusCode.value()).isEqualTo(200)
        assertThat(data(detail)["definitionHash"]).isEqualTo(registered["definitionHash"])
    }

    @Test
    fun `create-only registration preserves an existing definition and hash`() {
        val original = validDefinition("wf-create-only")
        val registered = postJson(REGISTER_PATH, adminToken, original)
        val headers = jsonHeaders(adminToken).apply { set("If-None-Match", "*") }
        val rejected = restTemplate.exchange(
            REGISTER_PATH, HttpMethod.POST,
            HttpEntity(original + ("title" to "Must not overwrite"), headers), mapType(),
        )
        assertThat(rejected.statusCode.value()).isEqualTo(409)
        assertThat(errorCode(rejected)).isEqualTo("WORKFLOW_DEFINITION_ALREADY_EXISTS")
        val detail = data(get("$REGISTER_PATH/wf-create-only/1.0.0", adminToken))
        assertThat(detail["definitionHash"]).isEqualTo(data(registered)["definitionHash"])
        assertThat(detail["definition"]).isEqualTo(original)
    }

    @Test
    fun `concurrent create-only registrations have one winner without overwriting`() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        val definitions = listOf(
            validDefinition("wf-create-race") + ("title" to "First editor"),
            validDefinition("wf-create-race") + ("title" to "Second editor"),
        )
        try {
            val futures = definitions.map { definition ->
                executor.submit<ResponseEntity<Map<String, Any?>>> {
                    ready.countDown()
                    check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    restTemplate.exchange(
                        REGISTER_PATH, HttpMethod.POST,
                        HttpEntity(definition, jsonHeaders(adminToken).apply { set("If-None-Match", "*") }), mapType(),
                    )
                }
            }
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            val responses = futures.map { it.get(30, java.util.concurrent.TimeUnit.SECONDS) }
            assertThat(responses.map { it.statusCode.value() }).containsExactlyInAnyOrder(201, 409)
            val winner = responses.indexOfFirst { it.statusCode.value() == 201 }
            val detail = data(get("$REGISTER_PATH/wf-create-race/1.0.0", adminToken))
            assertThat(detail["definition"]).isEqualTo(definitions[winner])
            assertThat(detail["definitionHash"]).isEqualTo(data(responses[winner])["definitionHash"])
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `create-only accepts a new version while preserving the original`() {
        val original = validDefinition("wf-contract-edit")
        val baseline = postJson(REGISTER_PATH, adminToken, original)
        val steps = original["steps"] as List<*>
        @Suppress("UNCHECKED_CAST")
        val step = steps.single() as Map<String, Any?>
        val edited = original + mapOf(
            "version" to "2.0.0",
            "steps" to listOf(step + mapOf("entry" to listOf("Read evidence"), "mission" to "Review evidence", "exit" to listOf("Decide"))),
        )
        val response = restTemplate.exchange(
            REGISTER_PATH, HttpMethod.POST,
            HttpEntity(edited, jsonHeaders(adminToken).apply { set("If-None-Match", "*") }), mapType(),
        )
        assertThat(response.statusCode.value()).isEqualTo(201)
        assertThat(data(response)["definitionHash"]).isNotEqualTo(data(baseline)["definitionHash"])
        assertThat(data(get("$REGISTER_PATH/wf-contract-edit/1.0.0", adminToken))["definition"]).isEqualTo(original)
        assertThat(data(get("$REGISTER_PATH/wf-contract-edit/2.0.0", adminToken))["definition"]).isEqualTo(edited)
    }

    @Test
    fun `JSON and upload reject version replacement but allow identical registration`() {
        val original = validDefinition("wf-immutable")
        val registered = postJson(REGISTER_PATH, adminToken, original)
        assertThat(postJson(REGISTER_PATH, adminToken, original).statusCode.value()).isEqualTo(201)
        val changed = original + ("title" to "Different business instructions")
        val json = postJson(REGISTER_PATH, adminToken, changed)
        val upload = postMultipart(UPLOAD_PATH, adminToken, "changed.json", objectMapperJson(changed))
        listOf(json, upload).forEach { response ->
            assertThat(response.statusCode.value()).isEqualTo(409)
            assertThat(errorCode(response)).isEqualTo("WORKFLOW_DEFINITION_ALREADY_EXISTS")
        }
        val detail = data(get("$REGISTER_PATH/wf-immutable/1.0.0", adminToken))
        assertThat(detail["definitionHash"]).isEqualTo(data(registered)["definitionHash"])
        assertThat(detail["definition"]).isEqualTo(original)
    }

    @Test
    fun `admin uploads a JSON definition file`() {
        val response = postMultipart(UPLOAD_PATH, adminToken, "wf-upload.json", objectMapperJson(validDefinition("wf-upload")))
        assertThat(response.statusCode.value()).isEqualTo(201)
        assertThat(data(response)["workflowType"]).isEqualTo("wf-upload")

        val detail = get("$REGISTER_PATH/wf-upload/1.0.0", adminToken)
        assertThat(detail.statusCode.value()).isEqualTo(200)
        assertThat(data(detail)["workflowType"]).isEqualTo("wf-upload")
    }

    @Test
    fun `invalid JSON and non-conforming definitions are rejected with 400`() {
        val invalidJson = postMultipart(UPLOAD_PATH, adminToken, "broken.json", "this is not json")
        assertThat(invalidJson.statusCode.value()).isEqualTo(400)
        assertThat(errorCode(invalidJson)).isEqualTo("WORKFLOW_DEFINITION_INVALID")

        val invalidDefinition = postMultipart(
            UPLOAD_PATH,
            adminToken,
            "invalid.json",
            objectMapperJson(mapOf("schemaVersion" to "1", "workflowType" to "bad", "version" to "1.0.0")),
        )
        assertThat(invalidDefinition.statusCode.value()).isEqualTo(400)
        assertThat(errorCode(invalidDefinition)).isEqualTo("INVALID_VALUE")

        val invalidRegister = postJson(
            REGISTER_PATH,
            adminToken,
            mapOf("schemaVersion" to "1", "workflowType" to "bad", "version" to "1.0.0"),
        )
        assertThat(invalidRegister.statusCode.value()).isEqualTo(400)
        assertThat(errorCode(invalidRegister)).isEqualTo("INVALID_VALUE")
    }

    @Test
    fun `admin deletes a definition by type and version`() {
        postJson(REGISTER_PATH, adminToken, validDefinition())

        val deleted = delete("$REGISTER_PATH/wf-admin/1.0.0", adminToken)
        assertThat(deleted.statusCode.value()).isEqualTo(200)
        assertThat(data(deleted)["deleted"]).isEqualTo(true)

        val detail = get("$REGISTER_PATH/wf-admin/1.0.0", adminToken)
        assertThat(detail.statusCode.value()).isEqualTo(404)

        val list = get(REGISTER_PATH, adminToken)
        assertThat(listItems(list).map { it["workflowType"] }).doesNotContain("wf-admin")
    }

    @Test
    fun `deleting an unknown definition returns 404 WORKFLOW_DEFINITION_NOT_FOUND`() {
        val response = delete("$REGISTER_PATH/absent/9.9.9", adminToken)
        assertThat(response.statusCode.value()).isEqualTo(404)
        assertThat(errorCode(response)).isEqualTo("WORKFLOW_DEFINITION_NOT_FOUND")
    }

    @Test
    fun `definition deletion protects exact org pins across workstreams and all lifecycle states`() {
        val cases = listOf("active", "removed", "purged", "orphan", "parent")
        cases.forEach { lifecycle ->
            val type = "protected-$lifecycle"
            postJson(REGISTER_PATH, adminToken, validDefinition(type))
            postJson(REGISTER_PATH, adminToken, validDefinition(type, "2.0.0"))
            val organizationId = neo4jDriver.session().use { session ->
                session.run("MATCH (d:WorkflowDefinition {workflowType: ${'$'}type}) RETURN d.organizationId AS org LIMIT 1", mapOf("type" to type))
                    .single()["org"].asString()
            }
            neo4jDriver.session().use { session ->
                val parameters = mapOf(
                    "org" to organizationId, "id" to "pin-$lifecycle", "lifecycle" to lifecycle,
                    "instance" to objectMapperJson(mapOf("workflowType" to type, "definitionVersion" to "1.0.0")),
                    "projection" to objectMapperJson(mapOf("workflowType" to type)),
                    "versions" to objectMapperJson(mapOf(type to "1.0.0")),
                )
                val query = when (lifecycle) {
                    "parent" -> "CREATE (:FactoryRun {id: ${'$'}id, organizationId: ${'$'}org, workstreamId: 'other-workstream', status: 'completed', flowVersions: ${'$'}versions})"
                    "orphan" -> "CREATE (:WorkflowInstance {id: ${'$'}id, organizationId: ${'$'}org, instance: ${'$'}instance})"
                    else -> "CREATE (:WorkflowProjection {id: ${'$'}id, organizationId: ${'$'}org, workstreamId: 'other-workstream', lifecycleState: ${'$'}lifecycle, projection: ${'$'}projection, definitionVersion: '1.0.0'})"
                }
                session.run(query, parameters).consume()
            }
            val response = delete("$REGISTER_PATH/$type/1.0.0", adminToken)
            assertThat(response.statusCode.value()).isEqualTo(409)
            assertThat(errorCode(response)).isEqualTo("WORKFLOW_DEFINITION_IN_USE")
            assertThat(get("$REGISTER_PATH/$type/1.0.0", adminToken).statusCode.value()).isEqualTo(200)
            assertThat(delete("$REGISTER_PATH/$type/2.0.0", adminToken).statusCode.value()).isEqualTo(200)
        }
    }

    @Test
    fun `another organization pin does not block definition deletion`() {
        postJson(REGISTER_PATH, adminToken, validDefinition("org-isolated-delete"))
        neo4jDriver.session().use { session ->
            session.run("CREATE (:FactoryRun {id: 'foreign-pin', organizationId: 'foreign-org', flowVersions: ${'$'}versions})",
                mapOf("versions" to objectMapperJson(mapOf("org-isolated-delete" to "1.0.0")))).consume()
        }
        assertThat(delete("$REGISTER_PATH/org-isolated-delete/1.0.0", adminToken).statusCode.value()).isEqualTo(200)
    }

    @Autowired
    private lateinit var deletionGuard: io.whozoss.factory.workflow.persistence.WorkflowDefinitionDeletionGuard

    @Autowired
    private lateinit var transactionManager: org.springframework.transaction.PlatformTransactionManager

    @Test
    fun `concurrent pin and deletion serialize on the definition before checking references`() {
        postJson(REGISTER_PATH, adminToken, validDefinition("pin-delete-race"))
        val organizationId = neo4jDriver.session().use { session ->
            session.run("MATCH (d:WorkflowDefinition {workflowType: 'pin-delete-race'}) RETURN d.organizationId AS org")
                .single()["org"].asString()
        }
        val locked = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val pin = executor.submit {
                org.springframework.transaction.support.TransactionTemplate(transactionManager).execute {
                    deletionGuard.assertPinsAvailable(organizationId, mapOf("pin-delete-race" to "1.0.0"))
                    locked.countDown()
                    check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    // Same transaction as the lock, using the production client.
                    neo4jClient.query("CREATE (:FactoryRun {id: 'race-pin', organizationId: ${'$'}org, flowVersions: ${'$'}versions})")
                        .bindAll(mapOf("org" to organizationId, "versions" to objectMapperJson(mapOf("pin-delete-race" to "1.0.0")))).run()
                }
            }
            check(locked.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val delete = executor.submit<ResponseEntity<Map<String, Any?>>> {
                delete("$REGISTER_PATH/pin-delete-race/1.0.0", adminToken)
            }
            release.countDown()
            pin.get(30, java.util.concurrent.TimeUnit.SECONDS)
            val response = delete.get(30, java.util.concurrent.TimeUnit.SECONDS)
            assertThat(response.statusCode.value()).isEqualTo(409)
            assertThat(errorCode(response)).isEqualTo("WORKFLOW_DEFINITION_IN_USE")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `a deletion winning before a pin refuses the stale catalog`() {
        postJson(REGISTER_PATH, adminToken, validDefinition("delete-before-pin"))
        val organizationId = neo4jDriver.session().use { session ->
            session.run("MATCH (d:WorkflowDefinition {workflowType: 'delete-before-pin'}) RETURN d.organizationId AS org")
                .single()["org"].asString()
        }
        assertThat(delete("$REGISTER_PATH/delete-before-pin/1.0.0", adminToken).statusCode.value()).isEqualTo(200)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            org.springframework.transaction.support.TransactionTemplate(transactionManager).execute {
                deletionGuard.assertPinsAvailable(organizationId, mapOf("delete-before-pin" to "1.0.0"))
            }
        }.isInstanceOf(io.whozoss.factory.workflow.domain.WorkflowException::class.java)
            .hasMessageContaining("unavailable")
    }

    @Autowired
    private lateinit var neo4jClient: org.springframework.data.neo4j.core.Neo4jClient

    private fun objectMapperJson(value: Any?): String =
        com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value)

    companion object {
        private const val REGISTER_PATH = "/api/factory/workflow-definitions"
        private const val UPLOAD_PATH = "/api/factory/workflow-definitions/upload"
    }
}
