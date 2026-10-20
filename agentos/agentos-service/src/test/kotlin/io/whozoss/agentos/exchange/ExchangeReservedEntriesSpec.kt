package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The Exchange protects whatever names a feature reserves, without knowing the feature.
 *
 * `.cache-owned` stands for any contribution: the Exchange has no rule of its own for it.
 */
class ExchangeReservedEntriesSpec :
    StringSpec({

        val reserved = listOf(ExchangeReservedEntries { setOf(".cache-owned") })
        val service = ExchangeStorageService(ExchangeStorageConfigProperties(), reserved)

        fun root() =
            Files.createTempDirectory("agentos-exchange-reserved-").also {
                it.resolve("notes.md").writeText("notes\n")
                it.resolve(".cache-owned").createDirectories()
                it.resolve(".cache-owned/state").writeText("state\n")
            }

        "a reserved entry is neither readable nor writable" {
            val root = root()
            shouldThrow<InvalidExchangePathException> { service.readContent(root, ".cache-owned/state") }
            shouldThrow<InvalidExchangePathException> { service.delete(root, ".CACHE-OWNED") }
        }

        "a reserved entry is not listed" {
            val root = root()
            service.listDirectory(root, "", 0, 100).first.map { it.name } shouldContainExactly listOf("notes.md")
        }

        "without contributions nothing is reserved" {
            val root = root()
            ExchangeStorageService(ExchangeStorageConfigProperties(), emptyList())
                .listDirectory(root, "", 0, 100).first.map { it.name } shouldContainExactly listOf(".cache-owned", "notes.md")
        }
    })
