package io.whozoss.agentos.plugins.http.response

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.plugins.http.openapi.json

/**
 * Semantics ported from `libs/integrations/http/src/lib/http.tools.ts` (`filterResponse`, `applyKeep`,
 * `applyIgnore`): arrays are always recursed into, `*` expands the keys of an object, `keepPaths` wins over
 * `ignorePaths`, a repeated path head merges into the already kept object. Two documented differences: `*`
 * applied to an array iterates its elements (what the TS doc comment promises) instead of expanding the keys
 * of each element, and a repeated head also merges through an array (`results.id` + `results.subject`),
 * where the TS code lets the last path replace the first.
 */
class PathFilterUnitSpec : StringSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val ticket = json(
        """
        {
          "ticket": { "id": 1, "subject": "Login broken", "via": { "channel": "web" }, "custom_fields": [] },
          "count": 1
        }
        """,
    )

    fun filter(node: String, keep: List<String> = emptyList(), ignore: List<String> = emptyList()): String =
        PathFilter.filter(json(node), keepPaths = keep, ignorePaths = ignore).toString()

    "no paths returns the data unchanged" {
        PathFilter.filter(ticket, keepPaths = emptyList(), ignorePaths = emptyList()) shouldBe ticket
    }

    "keepPaths keeps only the listed paths" {
        filter("""{"a":1,"b":{"c":2,"d":3},"e":4}""", keep = listOf("a", "b.c")) shouldBe """{"a":1,"b":{"c":2}}"""
    }

    "keepPaths with a repeated head merges into the kept object" {
        filter("""{"b":{"c":2,"d":3,"e":5}}""", keep = listOf("b.c", "b.d")) shouldBe """{"b":{"c":2,"d":3}}"""
    }

    "keepPaths with a repeated head merges through an array" {
        filter(
            """{"results":[{"id":1,"subject":"s","x":"a"},{"id":2,"subject":"t","x":"b"}]}""",
            keep = listOf("results.id", "results.subject"),
        ) shouldBe """{"results":[{"id":1,"subject":"s"},{"id":2,"subject":"t"}]}"""
    }

    "keepPaths merges a star head with a literal head on the same key" {
        filter("""{"a":{"id":1,"name":"n","x":1},"b":{"id":2,"x":2}}""", keep = listOf("*.id", "a.name")) shouldBe
            """{"a":{"id":1,"name":"n"},"b":{"id":2}}"""
    }

    "keepPaths keeps the whole value when one of the repeated heads has no tail" {
        filter("""{"a":{"id":1,"x":1}}""", keep = listOf("a.id", "a")) shouldBe """{"a":{"id":1,"x":1}}"""
    }

    "keepPaths is applied to every element of an array (arrays are transparent)" {
        filter("""{"results":[{"id":1,"x":"a"},{"id":2,"x":"b"}]}""", keep = listOf("results.id")) shouldBe
            """{"results":[{"id":1},{"id":2}]}"""
    }

    "keepPaths with a star iterates the elements of an array" {
        filter("""{"results":[{"id":1,"x":"a"},{"id":2,"x":"b"}],"count":2}""", keep = listOf("results.*.id", "count"))
            .shouldBe("""{"results":[{"id":1},{"id":2}],"count":2}""")
    }

    "keepPaths with a star expands the keys of an object" {
        filter("""{"byId":{"a":{"id":1,"x":1},"b":{"id":2,"x":2}}}""", keep = listOf("byId.*.id")) shouldBe
            """{"byId":{"a":{"id":1},"b":{"id":2}}}"""
    }

    "keepPaths keeps a primitive reached with a remaining tail (TS parity)" {
        filter("""{"a":{"b":1}}""", keep = listOf("a.b.c")) shouldBe """{"a":{"b":1}}"""
    }

    "keepPaths ignores a missing key and an empty head" {
        filter("""{"a":1}""", keep = listOf("missing", "", "a")) shouldBe """{"a":1}"""
    }

    "keepPaths on a root array filters each element" {
        filter("""[{"id":1,"x":1},{"id":2,"x":2}]""", keep = listOf("id")) shouldBe """[{"id":1},{"id":2}]"""
    }

    "keepPaths wins over ignorePaths" {
        filter("""{"a":1,"b":2}""", keep = listOf("a"), ignore = listOf("a")) shouldBe """{"a":1}"""
    }

    "ignorePaths removes the listed paths" {
        PathFilter.filter(ticket, keepPaths = emptyList(), ignorePaths = listOf("ticket.via", "ticket.custom_fields"))
            .toString() shouldBe """{"ticket":{"id":1,"subject":"Login broken"},"count":1}"""
    }

    "ignorePaths recurses into arrays and expands a star" {
        filter("""{"items":[{"id":1,"meta":{"a":1,"b":2}}]}""", ignore = listOf("items.meta.*")) shouldBe
            """{"items":[{"id":1,"meta":{}}]}"""
    }

    "ignorePaths with a star iterates the elements of an array" {
        filter("""{"items":[{"id":1,"x":1},{"id":2,"x":2}]}""", ignore = listOf("items.*.x")) shouldBe
            """{"items":[{"id":1},{"id":2}]}"""
    }

    "ignorePaths leaves a missing key and a primitive alone" {
        filter("""{"a":1,"b":"text"}""", ignore = listOf("missing", "b.c")) shouldBe """{"a":1,"b":"text"}"""
    }

    "a primitive root is returned as is" {
        filter(""""text"""", keep = listOf("a")) shouldBe "\"text\""
    }
})
