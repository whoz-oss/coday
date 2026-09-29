package io.whozoss.factory.workflow.web

import io.whozoss.factory.DomainIntegrationTest
import io.whozoss.factory.config.FactoryProperties
import io.whozoss.factory.web.TestJwt
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
 * HTTP integration tests of the admin surface of the workflow-definition
 * registry, against a real servlet container and a real PostgreSQL instance.
 *
 * They pin the exact contracts:
 *   - every mutation (`POST`, `POST /upload`, `DELETE`) is admin-guarded and a
 *     non-admin caller gets a `403 FORBIDDEN_ADMIN_REQUIRED`;
 *   - an admin can register definitions (`POST`), upload JSON files
 *     (`POST /upload`), list/get them and delete one by `(type, version)`;
 *   - invalid JSON and non-conforming definitions are rejected with a clean
 *     `400` and a readable machine code.
 */
class WorkflowDefinitionAdminIntegrationTest : DomainIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var factoryProperties: FactoryProperties

    private val adminToken: String
        get() = TestJwt.issueJwt(
            mapOf(
                "principalId" to "admin-user",
                "principalType" to "human",
                "scopes" to listOf("admin:*"),
            ),
            factoryProperties.security.fakeIdpSecret,
        )

    private val memberToken: String
        get() = TestJwt.issueJwt(
            mapOf("principalId" to "member-user", "principalType" to "human"),
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
    fun `non-admin callers get 403 FORBIDDEN_ADMIN_REQUIRED on every definition mutation`() {
        val register = postJson(REGISTER_PATH, memberToken, validDefinition())
        assertThat(register.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(register)).isEqualTo("FORBIDDEN_ADMIN_REQUIRED")

        val upload = postMultipart(UPLOAD_PATH, memberToken, "wf.json", objectMapperJson(validDefinition()))
        assertThat(upload.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(upload)).isEqualTo("FORBIDDEN_ADMIN_REQUIRED")

        val delete = delete("$REGISTER_PATH/wf-admin/1.0.0", memberToken)
        assertThat(delete.statusCode.value()).isEqualTo(403)
        assertThat(errorCode(delete)).isEqualTo("FORBIDDEN_ADMIN_REQUIRED")

        // Nothing was persisted by the refused calls.
        val list = get(REGISTER_PATH, adminToken)
        assertThat(listItems(list)).isEmpty()
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

    private fun objectMapperJson(value: Any?): String =
        com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value)

    companion object {
        private const val REGISTER_PATH = "/api/factory/workflow-definitions"
        private const val UPLOAD_PATH = "/api/factory/workflow-definitions/upload"
    }
}
