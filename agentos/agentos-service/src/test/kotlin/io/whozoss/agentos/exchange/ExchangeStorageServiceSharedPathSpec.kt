package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.namespace.Namespace
import io.whozoss.agentos.namespace.NamespaceRepository
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import io.whozoss.agentos.sdk.entity.EntityMetadata
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * `Namespace.sharedExchangePath` replaces `<mountRoot>/<namespaceId>/shared` for the namespace-shared
 * root, and nothing else: containment, symlink and reserved-entry rules apply to the new root exactly as
 * to the default one, and everything else the service keeps for the namespace stays where it was.
 */
class ExchangeStorageServiceSharedPathSpec : StringSpec({
    val mount = Files.createTempDirectory("agentos-shared-path-mount-")
    val namespaceId = UUID.randomUUID()

    fun service(sharedExchangePath: String?): ExchangeStorageService {
        val repository = mockk<NamespaceRepository>()
        every { repository.findByIds(listOf(namespaceId)) } returns
            listOf(Namespace(metadata = EntityMetadata(id = namespaceId), name = "ns", sharedExchangePath = sharedExchangePath))
        return ExchangeStorageService(
            ExchangeStorageConfigProperties(mountRoot = mount.toString()),
            listOf(object : ExchangeReservedEntries {
                override fun names() = setOf(".git")
            }),
            repository,
        )
    }

    "without a configured path the shared root is the default one under the mount root" {
        val storage = service(null)

        storage.namespaceRoot(namespaceId) shouldBe mount.resolve(namespaceId.toString()).resolve("shared")
        storage.defaultNamespaceRoot(namespaceId) shouldBe storage.namespaceRoot(namespaceId)
    }

    "a configured absolute path replaces the shared root" {
        val folder = Files.createTempDirectory("agentos-own-folder-")

        service(folder.toString()).namespaceRoot(namespaceId) shouldBe folder
    }

    "a blank or relative path is ignored rather than guessed" {
        listOf("", "   ", "relative/folder", "../escape").forEach { raw ->
            service(raw).namespaceRoot(namespaceId) shouldBe mount.resolve(namespaceId.toString()).resolve("shared")
        }
    }

    "only the shared root moves: the namespace directory and the case roots keep their place" {
        val storage = service(Files.createTempDirectory("agentos-own-folder-").toString())

        storage.namespaceDirectory(namespaceId) shouldBe mount.resolve(namespaceId.toString())
        storage
            .caseRoot(namespaceId, UUID.randomUUID(), java.time.Instant.parse("2026-01-02T03:04:05Z"))
            .startsWith(mount.resolve(namespaceId.toString()).resolve("cases")) shouldBe true
    }

    "files are written, listed and read under the configured folder" {
        val folder = Files.createTempDirectory("agentos-own-folder-")
        val storage = service(folder.toString())
        val root = storage.namespaceRoot(namespaceId)

        storage.writeNew(root, "notes/todo.md", "hello".toByteArray(), ExchangeScope.NAMESPACE)

        Files.readString(folder.resolve("notes/todo.md")) shouldBe "hello"
        storage.listManifest(root, ExchangeScope.NAMESPACE).map { it.path } shouldBe listOf("notes/todo.md")
        storage.readContent(root, "notes/todo.md").content shouldBe "hello"
        Files.exists(mount.resolve(namespaceId.toString()).resolve("shared")) shouldBe false
    }

    "traversal, symlink escapes and reserved entries are refused under the configured folder" {
        val outside = Files.createTempDirectory("agentos-outside-")
        Files.writeString(outside.resolve("secret.txt"), "secret")
        val folder = Files.createTempDirectory("agentos-own-folder-")
        Files.createSymbolicLink(folder.resolve("link"), outside)
        Files.createDirectories(folder.resolve(".git"))
        val storage = service(folder.toString())
        val root = storage.namespaceRoot(namespaceId)

        shouldThrow<InvalidExchangePathException> { storage.readContent(root, "../${outside.fileName}/secret.txt") }
        shouldThrow<InvalidExchangePathException> { storage.readContent(root, "link/secret.txt") }
        shouldThrow<InvalidExchangePathException> { storage.readContent(root, ".git/config") }
    }

    "a service without a namespace repository keeps the default layout" {
        val storage = ExchangeStorageService(ExchangeStorageConfigProperties(mountRoot = mount.toString()), emptyList())

        storage.namespaceRoot(namespaceId) shouldBe Path.of(mount.toString(), namespaceId.toString(), "shared")
    }
})
