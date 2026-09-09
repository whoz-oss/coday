package io.whozoss.agentos.util

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe

class ListUtilsTest :
    DescribeSpec({
        describe("mapWhile") {
            it("maps all elements when predicate always holds") {
                val result =
                    (1..4).mapWhile(
                        transform = { it * 2 },
                        predicate = { _, r -> r < 10 },
                    )
                result shouldBe listOf(2, 4, 6, 8)
            }

            it("stops after first failing element and includes it") {
                val result =
                    (1..4).mapWhile(
                        transform = { it * 2 },
                        predicate = { _, r -> r < 5 },
                    )
                result shouldBe listOf(2, 4, 6)
            }

            it("includes the first element when it fails the predicate") {
                val result =
                    (1..4).mapWhile(
                        transform = { it * 10 },
                        predicate = { _, r -> r < 5 },
                    )
                result shouldBe listOf(10)
            }

            it("returns empty list for empty input") {
                val result =
                    emptyList<Int>().mapWhile(
                        transform = { it },
                        predicate = { _, _ -> true },
                    )
                result shouldBe emptyList()
            }

            it("predicate receives both the original item and the transformed result") {
                val result =
                    listOf("a", "bb", "ccc", "dddd").mapWhile(
                        transform = { it.length },
                        predicate = { item, r -> item.length == r && r < 3 },
                    )
                result shouldBe listOf(1, 2, 3)
            }
        }
    })
