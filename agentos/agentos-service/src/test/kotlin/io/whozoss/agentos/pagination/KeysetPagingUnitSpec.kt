package io.whozoss.agentos.pagination

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.exception.BadRequestException
import java.util.Base64

class KeysetPagingUnitSpec :
    StringSpec({
        val sorted = (1..10).map { "id-%02d".format(it) }

        /** Reads one page over [rows] with a fetch that, like the repositories, honours the cursor and the size. */
        fun read(
            rows: List<String> = sorted,
            limit: Int? = null,
            next: String? = null,
            asked: MutableList<Pair<String?, Int>> = mutableListOf(),
            counts: MutableList<Unit>? = null,
        ) = KeysetPaging.read(
            limit = limit,
            next = next,
            externalIdOf = { it: String -> it },
            totalCount = { counts?.add(Unit); rows.size.toLong() },
            fetch = { after, size ->
                asked += after to size
                rows.filter { after == null || it > after }.take(size)
            },
        )

        "walks first, middle and last pages, each starting strictly after the previous page's last id" {
            val asked = mutableListOf<Pair<String?, Int>>()
            val counts = mutableListOf<Unit>()
            val first = read(limit = 4, asked = asked, counts = counts)
            val middle = read(limit = 4, next = first.next, asked = asked, counts = counts)
            val last = read(limit = 4, next = middle.next, asked = asked, counts = counts)

            listOf(first, middle, last).map { it.items } shouldContainExactly listOf(sorted.take(4), sorted.subList(4, 8), sorted.drop(8))
            listOf(first, middle, last).map { it.totalCount } shouldContainExactly listOf(10L, 10L, 10L)
            asked.map { it.first } shouldContainExactly listOf(null, "id-04", "id-08")
            last.next shouldBe null
            counts.size shouldBe 1 // the total rides in the cursor: only the first page counts
        }

        "asks for one extra row, so a result that is an exact multiple of the limit ends with a null next" {
            val asked = mutableListOf<Pair<String?, Int>>()
            val first = read(sorted.take(8), limit = 4, asked = asked)
            val last = read(sorted.take(8), limit = 4, next = first.next, asked = asked)

            asked.map { it.second } shouldContainExactly listOf(5, 5)
            last.items shouldContainExactly sorted.subList(4, 8)
            last.next shouldBe null
        }

        "defaults the limit to 100" {
            val asked = mutableListOf<Pair<String?, Int>>()

            read(limit = null, asked = asked)

            asked.single().second shouldBe 101
        }

        "an empty result has no items and no cursor" {
            val page = read(emptyList(), limit = 4)

            page.items shouldContainExactly emptyList()
            page.next shouldBe null
        }

        "a cursor round-trips blank and non-ASCII external ids" {
            listOf("", " ", "é-ü", "日本語", "a:b/c+d=", ":", "7:x").forEach { id ->
                val first = KeysetPaging.read(1, null, { it: String -> it }, { 2L }, { _, _ -> listOf(id, "next-row") })
                var resumedAfter: String? = "unset"

                KeysetPaging.read(1, first.next, { it: String -> it }, { 2L }, { after, _ -> resumedAfter = after; emptyList() })

                resumedAfter shouldBe id
            }
        }

        "rejects a cursor that is not base64, lacks the prefix, has a non-numeric or negative total, or is the old format, with 400" {
            fun cursor(raw: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(raw.toByteArray())

            listOf("***not base64***", cursor("id-03"), cursor("after:id-03"), cursor("after:abc:id-03"), cursor("after:-1:id-03"), cursor("after:5"))
                .forEach { shouldThrow<BadRequestException> { read(next = it) } }
        }
    })
