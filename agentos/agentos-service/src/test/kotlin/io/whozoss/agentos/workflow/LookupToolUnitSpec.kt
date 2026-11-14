package io.whozoss.agentos.workflow

import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.tool.StandardTool

/** Unit tests for [lookupTool]: the one rule by which the loop finds its search tool and its starter tool. */
class LookupToolUnitSpec :
    StringSpec({

        fun tools(vararg names: String): List<StandardTool<*>> =
            names.map { name -> mockk<StandardTool<*>>().also { every { it.name } returns name } }

        "a tool is found by its exact name, else by its name behind an integration prefix, and says when it is ambiguous" {
            forAll(
                row("exact", listOf("SearchTalents", "Other"), "SearchTalents", listOf("SearchTalents")),
                row("unique prefixed", listOf("USER_GROUP__SearchTalents", "Other"), "SearchTalents", listOf("USER_GROUP__SearchTalents")),
                row(
                    "exact preferred over prefixed",
                    listOf("USER_GROUP__SearchTalents", "SearchTalents"),
                    "SearchTalents",
                    listOf("SearchTalents"),
                ),
                row(
                    "ambiguous prefixed",
                    listOf("A__SearchTalents", "B__SearchTalents"),
                    "SearchTalents",
                    listOf("A__SearchTalents", "B__SearchTalents"),
                ),
                row("absent", listOf("Other", "A__Other"), "SearchTalents", emptyList<String>()),
                row("a longer name ending the same way is not a match", listOf("MySearchTalents"), "SearchTalents", emptyList<String>()),
                row("the prefixed name given as is", listOf("A__SearchTalents", "B__SearchTalents"), "A__SearchTalents", listOf("A__SearchTalents")),
                // FreeCaseStarter and FreeCaseStarterForUser must not be taken for one another
                row("the starter for a user is not the starter", listOf("CASE__FreeCaseStarterForUser"), "FreeCaseStarter", emptyList<String>()),
                row("the starter is not the starter for a user", listOf("CASE__FreeCaseStarter"), "FreeCaseStarterForUser", emptyList<String>()),
                row(
                    "the starter for a user is found among both",
                    listOf("CASE__FreeCaseStarter", "CASE__FreeCaseStarterForUser"),
                    "FreeCaseStarterForUser",
                    listOf("CASE__FreeCaseStarterForUser"),
                ),
            ) { _, available, name, expected ->
                lookupTool(tools(*available.toTypedArray()), name).map { it.name } shouldBe expected
            }
        }
    })
