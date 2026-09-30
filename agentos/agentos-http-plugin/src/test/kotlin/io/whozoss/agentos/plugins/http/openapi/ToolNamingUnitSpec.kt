package io.whozoss.agentos.plugins.http.openapi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class ToolNamingUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val nameRegex = Regex("^[A-Za-z0-9_-]{1,64}$")

    "uses the operationId as suffix after sanitising" {
        ToolNaming.suffixFor(operationId = "tickets.show", method = HttpMethod.GET, path = "/tickets/{id}") shouldBe
            "tickets_show"
        ToolNaming.suffixFor(operationId = "ListTickets", method = HttpMethod.GET, path = "/tickets") shouldBe
            "ListTickets"
        ToolNaming.suffixFor(operationId = "list-tickets", method = HttpMethod.GET, path = "/tickets") shouldBe
            "list-tickets"
    }

    "collapses underscore runs and trims edges so the suffix never contains a double underscore" {
        ToolNaming.suffixFor(operationId = "_a__b___c_", method = HttpMethod.GET, path = "/x") shouldBe "a_b_c"
        ToolNaming.suffixFor(operationId = "show ticket (v2)", method = HttpMethod.GET, path = "/x") shouldBe
            "show_ticket_v2"
    }

    "derives the suffix from method and path when operationId is absent" {
        ToolNaming.suffixFor(operationId = null, method = HttpMethod.GET, path = "/tickets/{ticket_id}") shouldBe
            "get_tickets_by_ticket_id"
        ToolNaming.suffixFor(operationId = null, method = HttpMethod.POST, path = "/tickets") shouldBe
            "post_tickets"
        val nested = "/api/v2/tickets/{id}/comments"
        ToolNaming.suffixFor(operationId = null, method = HttpMethod.GET, path = nested) shouldBe
            "get_api_v2_tickets_by_id_comments"
    }

    "falls back to method and path when the operationId sanitises to nothing" {
        ToolNaming.suffixFor(operationId = "!!!", method = HttpMethod.GET, path = "/tickets") shouldBe "get_tickets"
    }

    "builds the full name with a double underscore separator" {
        ToolNaming.toolName(configName = "zendesk", suffix = "ShowTicket") shouldBe "zendesk__ShowTicket"
    }

    "truncates a long suffix with a hash and keeps it unique" {
        val idA = "a".repeat(80)
        val idB = "a".repeat(79) + "b"
        val nameA = ToolNaming.toolName(configName = "zendesk", suffix = idA)
        val nameB = ToolNaming.toolName(configName = "zendesk", suffix = idB)
        nameA.length shouldBe 64
        nameB.length shouldBe 64
        nameA shouldMatch nameRegex
        nameA shouldNotBe nameB
        nameA.substringAfter("__") shouldMatch Regex("^a+_[0-9a-f]{4}$")
    }

    "does not create a double underscore when the truncation point ends with an underscore" {
        val suffix = "a".repeat(52) + "_" + "b".repeat(30)
        val name = ToolNaming.toolName(configName = "zendesk", suffix = suffix)
        name.length shouldBe 64
        name.substringAfter("__") shouldNotContain "__"
    }

    "rejects a config name that leaves no room for a suffix" {
        ToolNaming.toolName(configName = "c".repeat(56), suffix = "x") shouldBe "c".repeat(56) + "__x"
        shouldThrow<IllegalArgumentException> {
            ToolNaming.toolName(configName = "c".repeat(57), suffix = "x")
        }
    }

    "accepts an integration name made of letters, digits, '_' and '-' that leaves room for a suffix" {
        listOf("ZENDESK", "zendesk-prod", "a_b", "x", "c".repeat(56)).forEach { name ->
            ToolNaming.configNameProblem(name) shouldBe null
        }
    }

    "names the problem of an integration name that cannot prefix a tool name" {
        ToolNaming.configNameProblem("c".repeat(57)) shouldBe
            "integration name is too long: at most 56 characters are allowed"
        listOf("Zendesk Prod", "zendesk.prod", "caf\u00e9", "").forEach { name ->
            ToolNaming.configNameProblem(name) shouldBe
                "integration name must contain only letters, digits, '_' and '-' to prefix tool names"
        }
        ToolNaming.configNameProblem("A__B") shouldBe
            "integration name must not contain '__', the separator between the integration and the operation"
    }

    "assigns _2, _3 to colliding suffixes" {
        val suffixes = ToolNaming.assignUniqueSuffixes(
            configName = "cfg",
            rawSuffixes = listOf("show", "show", "show", "list"),
        )
        suffixes shouldBe listOf("show", "show_2", "show_3", "list")
        suffixes.forEach { ToolNaming.toolName(configName = "cfg", suffix = it) shouldBe "cfg__$it" }
    }

    "keeps collision resolution inside the length limit" {
        val long = "a".repeat(80)
        val suffixes = ToolNaming.assignUniqueSuffixes(configName = "cfg", rawSuffixes = listOf(long, long))
        suffixes.toSet().size shouldBe 2
        suffixes.forEach { ToolNaming.toolName(configName = "cfg", suffix = it).length shouldBe 64 }
    }

    "any operationId yields a valid full name and a suffix without double underscore" {
        checkAll(500, Arb.string(0..120)) { operationId ->
            val suffix = ToolNaming.suffixFor(operationId = operationId, method = HttpMethod.GET, path = "/a/{b}")
            val name = ToolNaming.toolName(configName = "zendesk", suffix = suffix)
            name shouldMatch nameRegex
            name.substringAfter("__") shouldNotContain "__"
            name.substringAfter("__").isNotEmpty() shouldBe true
        }
    }
})
