package io.whozoss.agentos.schedule

import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.whozoss.agentos.sdk.entity.EntityMetadata
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * MVC-layer test for [ScheduleController] — verifies that Bean Validation is
 * triggered by the Spring MVC dispatcher on create and update endpoints.
 *
 * Uses a full Spring Boot context (webEnvironment = MOCK) with the "test" profile
 * so that the dispatcher, message converters, and validation are all active.
 * The "test" profile enables in-memory persistence so no external services are needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScheduleControllerIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var scheduleService: ScheduleService

    private val namespaceId = UUID.randomUUID()

    init {

        // -------------------------------------------------------------------------
        // POST /api/schedules — create
        // -------------------------------------------------------------------------

        "POST /api/schedules with missing namespaceId returns 400" {
            mockMvc.perform(
                post("/api/schedules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "message": "wake up" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/schedules with blank message returns 400" {
            mockMvc.perform(
                post("/api/schedules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "message": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "POST /api/schedules with valid minimal payload returns 201" {
            mockMvc.perform(
                post("/api/schedules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "namespaceId": "$namespaceId", "message": "daily report" }""")
            ).andExpect(status().isCreated)
        }

        "POST /api/schedules with one-shot triggerAt returns 201" {
            mockMvc.perform(
                post("/api/schedules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "namespaceId": "$namespaceId",
                            "message": "check deployment",
                            "oneShot": true,
                            "triggerAt": "2025-06-01T09:00:00Z"
                        }
                        """.trimIndent()
                    )
            ).andExpect(status().isCreated)
        }

        "POST /api/schedules with intervalSchedule returns 201" {
            mockMvc.perform(
                post("/api/schedules")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "namespaceId": "$namespaceId",
                            "message": "weekly review",
                            "intervalSchedule": {
                                "startTimestamp": "2025-01-06T10:00:00Z",
                                "interval": "7d",
                                "daysOfWeek": [1]
                            }
                        }
                        """.trimIndent()
                    )
            ).andExpect(status().isCreated)
        }

        // -------------------------------------------------------------------------
        // PUT /api/schedules/{id} — update
        // -------------------------------------------------------------------------

        "PUT /api/schedules/{id} with blank message returns 400" {
            val id = UUID.randomUUID()

            mockMvc.perform(
                put("/api/schedules/$id")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{ "id": "$id", "namespaceId": "$namespaceId", "message": "" }""")
            ).andExpect(status().isBadRequest)
        }

        "PUT /api/schedules/{id} with valid payload returns 200" {
            val created = scheduleService.create(
                Schedule(
                    metadata = EntityMetadata(id = UUID.randomUUID()),
                    namespaceId = namespaceId,
                    message = "initial message",
                )
            )

            mockMvc.perform(
                put("/api/schedules/${created.id}")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                            "id": "${created.id}",
                            "namespaceId": "$namespaceId",
                            "message": "updated message",
                            "enabled": false
                        }
                        """.trimIndent()
                    )
            ).andExpect(status().isOk)
        }
    }
}
