package io.whozoss.agentos.usergroup

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserGroupControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userGroupService: UserGroupService

    private val namespaceId = UUID.randomUUID()

    init {

        "POST /api/user-groups/list with missing namespaceId returns 400" {
            mockMvc.perform(
                post("/api/user-groups/list")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups/list with invalid namespaceId returns 400" {
            mockMvc.perform(
                post("/api/user-groups/list")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "not-a-uuid" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups/list with valid namespaceId returns 200" {
            mockMvc.perform(
                post("/api/user-groups/list")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId" }""")
            ).andExpect(status().isOk)
        }

        "POST /api/user-groups with missing namespaceId returns 400" {
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "Group A" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups with blank name returns 400" {
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups with missing name returns 400" {
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups with invalid uuid in userIds returns 400" {
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "G", "userIds": ["not-a-uuid"] }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups with invalid uuid in agentIds returns 400" {
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "G", "agentIds": ["bad"] }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups with valid payload returns 201" {
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "Group A" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/user-groups with valid payload and userIds and agentIds returns 201" {
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "Group B", "userIds": ["${UUID.randomUUID()}"], "agentIds": ["${UUID.randomUUID()}"] }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/user-groups/{id} with missing namespaceId returns 400" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "To Update",
            ))
            mockMvc.perform(
                post("/api/user-groups/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "name": "Updated" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups/{id} with blank name returns 400" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "To Update 2",
            ))
            mockMvc.perform(
                post("/api/user-groups/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups/{id} with valid payload returns 200" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "To Update 3",
            ))
            mockMvc.perform(
                post("/api/user-groups/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "Updated 3" }""")
            ).andExpect(status().isOk)
        }

        "GET /api/user-groups/{id} with invalid userGroupId returns 400" {
            mockMvc.perform(
                get("/api/user-groups/not-a-uuid")
                    .param("namespaceId", namespaceId.toString())
            ).andExpect(status().isBadRequest)
        }

        "GET /api/user-groups/{id} with missing namespaceId returns 400" {
            mockMvc.perform(
                get("/api/user-groups/${UUID.randomUUID()}")
            ).andExpect(status().isBadRequest)
        }

        "GET /api/user-groups/{id} with valid params returns 200" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "To Get",
            ))
            mockMvc.perform(
                get("/api/user-groups/${created.id}")
                    .param("namespaceId", namespaceId.toString())
            ).andExpect(status().isOk)
        }

        "DELETE /api/user-groups/{id} with invalid userGroupId returns 400" {
            mockMvc.perform(
                delete("/api/user-groups/not-a-uuid")
                    .param("namespaceId", namespaceId.toString())
            ).andExpect(status().isBadRequest)
        }

        "DELETE /api/user-groups/{id} with missing namespaceId returns 400" {
            mockMvc.perform(
                delete("/api/user-groups/${UUID.randomUUID()}")
            ).andExpect(status().isBadRequest)
        }

        "DELETE /api/user-groups/{id} with valid params returns 204" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "To Delete",
            ))
            mockMvc.perform(
                delete("/api/user-groups/${created.id}")
                    .param("namespaceId", namespaceId.toString())
            ).andExpect(status().isNoContent)
        }

        "POST /api/user-groups returns userCount in response" {
            val result = mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "Count Test" }""")
            ).andExpect(status().isCreated)
                .andReturn()

            val body = result.response.contentAsString
            body.contains("\"userCount\":0") shouldBe true
        }

        "GET /api/user-groups/{id} returns computed userCount" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "Count Verify",
            ))
            val result = mockMvc.perform(
                get("/api/user-groups/${created.id}")
                    .param("namespaceId", namespaceId.toString())
            ).andExpect(status().isOk)
                .andReturn()

            val body = result.response.contentAsString
            body.contains("\"userCount\":0") shouldBe true
        }

        "POST /api/user-groups with name exceeding 254 chars returns 400" {
            val longName = "a".repeat(255)
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "$longName" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups with name of 254 chars returns 201" {
            val maxName = "a".repeat(254)
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "$maxName" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/user-groups with userExternalIds returns 201" {
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "Ext Group", "userExternalIds": ["ext-user-abc"] }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/user-groups/{id} with addUserExternalIds returns 200" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "For Ext Update",
            ))
            mockMvc.perform(
                post("/api/user-groups/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "For Ext Update", "addUserExternalIds": ["ext-new-user"] }""")
            ).andExpect(status().isOk)
        }

        "POST /api/user-groups with blank userExternalId returns 400" {
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "G", "userExternalIds": [""] }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups with userExternalId exceeding 254 chars returns 400" {
            val longExternalId = "a".repeat(255)
            mockMvc.perform(
                post("/api/user-groups")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "G", "userExternalIds": ["$longExternalId"] }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups/{id} with blank addUserExternalId returns 400" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "For Blank Ext",
            ))
            mockMvc.perform(
                post("/api/user-groups/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "For Blank Ext", "addUserExternalIds": [""] }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups/{id} with addUserExternalId exceeding 254 chars returns 400" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "For Long Ext",
            ))
            val longExternalId = "a".repeat(255)
            mockMvc.perform(
                post("/api/user-groups/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "For Long Ext", "addUserExternalIds": ["$longExternalId"] }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups/{id} with blank removeUserExternalId returns 400" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "For Blank Remove Ext",
            ))
            mockMvc.perform(
                post("/api/user-groups/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "For Blank Remove Ext", "removeUserExternalIds": [""] }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups/{id} with removeUserExternalId exceeding 254 chars returns 400" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "For Long Remove Ext",
            ))
            val longExternalId = "a".repeat(255)
            mockMvc.perform(
                post("/api/user-groups/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "For Long Remove Ext", "removeUserExternalIds": ["$longExternalId"] }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/user-groups/{id} with removeUserExternalIds returns 200" {
            val created = userGroupService.create(UserGroupCreateRequest(
                namespaceId = namespaceId,
                name = "For Remove Ext",
            ))
            mockMvc.perform(
                post("/api/user-groups/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "name": "For Remove Ext", "removeUserExternalIds": ["some-ext"] }""")
            ).andExpect(status().isOk)
        }


    }
}
