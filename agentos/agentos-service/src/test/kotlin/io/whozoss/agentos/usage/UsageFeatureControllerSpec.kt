package io.whozoss.agentos.usage

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.caseEvent.CaseEventService
import io.whozoss.agentos.caseFlow.CaseRepository
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.config.UsageConfigProperties
import io.whozoss.agentos.namespace.NamespaceService
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/** HTTP dispatch with real controllers and the real cost guard; authorization is covered by the integration spec. */
class UsageFeatureControllerSpec :
    StringSpec({
        "configuration exposes the default disabled setting and explicit enablement" {
            for (config in listOf(UsageConfigProperties(), UsageConfigProperties(enabled = true))) {
                val mvc = MockMvcBuilders.standaloneSetup(UsageConfigurationController(config)).build()
                mvc
                    .perform(get("/api/usage-configuration"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$.enabled").value(config.enabled))
            }
        }

        "disabled cost endpoints reject HTTP requests without reading data or interrupting a case" {
            val caseRepository = mockk<CaseRepository>()
            val events = mockk<CaseEventService>()
            val namespaces = mockk<NamespaceService>()
            val records = mockk<UsageRecordService>()
            val cases = mockk<CaseService>()
            val costs = RunCostService(caseRepository, events, namespaces, records)
            val mvc = MockMvcBuilders.standaloneSetup(RunCostController(costs, cases)).build()
            val caseId = UUID.randomUUID()
            val requests =
                listOf(
                    get("/api/cases/$caseId/run-cost"),
                    post("/api/cases/$caseId/run-cost/continue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"expectedThreshold":10}"""),
                    post("/api/cases/$caseId/run-cost/stop"),
                )
            requests.forEach { request ->
                val result = mvc.perform(request).andExpect(status().isServiceUnavailable).andReturn()
                (result.resolvedException as ResponseStatusException).reason shouldBe "Usage tracking is disabled"
            }
            verify { listOf(caseRepository, events, namespaces, records, cases) wasNot Called }
        }

        "every disabled usage record endpoint rejects HTTP requests before querying analytics" {
            val records = mockk<UsageRecordService>()
            val mvc = MockMvcBuilders.standaloneSetup(UsageRecordController(records)).build()
            val caseId = UUID.randomUUID()
            val namespaceId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val requests =
                listOf(
                    get("/api/usage-records/by-case/$caseId"),
                    get("/api/usage-records/aggregate/by-case/$caseId"),
                    get("/api/usage-records/aggregate/by-case-tree/$caseId"),
                    get("/api/usage-records/aggregate/by-user").param("userId", userId.toString()),
                    get("/api/usage-records/aggregate/by-agent"),
                    get("/api/usage-records/aggregate/by-model"),
                )
            requests.forEach { request ->
                val result =
                    mvc
                        .perform(
                            request
                                .param("namespaceId", namespaceId.toString())
                                .param("from", "2026-01-01T00:00:00Z")
                                .param("to", "2026-02-01T00:00:00Z"),
                        ).andExpect(status().isServiceUnavailable)
                        .andReturn()
                (result.resolvedException as ResponseStatusException).reason shouldBe "Usage tracking is disabled"
            }
            verify { records wasNot Called }
        }

        "explicit enablement allows usage records and cost interruption" {
            val config = UsageConfigProperties(enabled = true)
            val records = mockk<UsageRecordService>()
            val cases = mockk<CaseService>(relaxed = true)
            val costs = mockk<RunCostService>()
            val caseId = UUID.randomUUID()
            every { records.findByCaseId(caseId) } returns emptyList()
            val mvc =
                MockMvcBuilders
                    .standaloneSetup(UsageRecordController(records, config), RunCostController(costs, cases, config))
                    .build()

            mvc.perform(get("/api/usage-records/by-case/$caseId")).andExpect(status().isOk)
            mvc.perform(post("/api/cases/$caseId/run-cost/stop")).andExpect(status().isNoContent)
            verify(exactly = 1) { records.findByCaseId(caseId) }
            verify(exactly = 1) { cases.interruptCase(caseId) }
        }
    })
