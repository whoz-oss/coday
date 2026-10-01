package io.whozoss.agentos.exchange

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.whozoss.agentos.caseFlow.Case
import java.nio.file.Files
import java.time.Instant

class DefaultExchangeRootResolverSpec : StringSpec({
    val mountRoot = Files.createTempDirectory("default-exchange-root-")
    val storage = ExchangeStorageService(ExchangeStorageConfigProperties(mountRoot = mountRoot.toString()), emptyList())
    val resolver = DefaultExchangeRootResolver(storage)

    "a case keeps its own date-sharded directory, as before the resolver existed" {
        val case = Case(namespaceId = java.util.UUID.randomUUID())

        val root = resolver.resolve(case)

        root shouldBe ResolvedExchangeRoot(storage.caseRoot(case.namespaceId, case.id, case.metadata.created), case.id)
    }

    "resolving by identity gives the same directory as resolving the entity" {
        val created = Instant.parse("2026-01-02T03:04:05Z")
        val case = Case(metadata = io.whozoss.agentos.sdk.entity.EntityMetadata(created = created), namespaceId = java.util.UUID.randomUUID())

        resolver.resolve(case.id, case.namespaceId, created) shouldBe resolver.resolve(case)
    }

    "a mutation runs on the case's own directory" {
        val case = Case(namespaceId = java.util.UUID.randomUUID())

        resolver.withCaseMutation(case) { it.path } shouldBe storage.caseRoot(case.namespaceId, case.id, case.metadata.created)
    }
})
