package io.whozoss.agentos.exchange

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

/**
 * Unit tests for [ExchangeStorageService] exercising write/list/read against a temporary
 * mount root, plus the two security-relevant invariants:
 * - the namespace-shared manifest never sees case-private files (physical separation);
 * - path traversal is rejected (the resolved path must stay within the scope root).
 */
class ExchangeStorageServiceSpec :
    StringSpec({

        fun newService(): ExchangeStorageService {
            val mountRoot = Files.createTempDirectory("exchange-test")
            return ExchangeStorageService(ExchangeStorageConfigProperties(mountRoot = mountRoot.toString()))
        }

        val createdAt = Instant.parse("2025-12-15T10:30:00Z")

        "writeNew creates the file and returns its entry" {
            val service = newService()
            val root = service.caseRoot(UUID.randomUUID(), UUID.randomUUID(), createdAt)

            val entry = service.writeNew(root, "report.txt", "hello".toByteArray(), ExchangeScope.CASE)

            entry.path shouldBe "report.txt"
            entry.filename shouldBe "report.txt"
            entry.size shouldBe 5L
            entry.scope shouldBe ExchangeScope.CASE
            (entry.etag != null) shouldBe true
            Files.exists(root.resolve("report.txt")) shouldBe true
            service.readContent(root, "report.txt").content shouldBe "hello"
        }

        "writeNew on an existing path throws FileExistsException" {
            val service = newService()
            val root = service.namespaceRoot(UUID.randomUUID())
            service.writeNew(root, "dup.txt", "one".toByteArray(), ExchangeScope.NAMESPACE)

            shouldThrow<FileExistsException> {
                service.writeNew(root, "dup.txt", "two".toByteArray(), ExchangeScope.NAMESPACE)
            }
            // original content is preserved
            service.readContent(root, "dup.txt").content shouldBe "one"
        }

        "listManifest lists files under a case root with correct relative paths" {
            val service = newService()
            val root = service.caseRoot(UUID.randomUUID(), UUID.randomUUID(), createdAt)
            service.writeNew(root, "a.txt", "a".toByteArray(), ExchangeScope.CASE)
            service.writeNew(root, "nested/b.txt", "b".toByteArray(), ExchangeScope.CASE)

            val files = service.listManifest(root, ExchangeScope.CASE)

            files.map { it.path } shouldContainExactlyInAnyOrder listOf("a.txt", "nested/b.txt")
            files.forEach { it.scope shouldBe ExchangeScope.CASE }
        }

        "listManifest returns empty for a root that does not exist" {
            val service = newService()
            val root = service.namespaceRoot(UUID.randomUUID())

            service.listManifest(root, ExchangeScope.NAMESPACE).shouldHaveSize(0)
        }

        "namespace shared listing does not include case-scoped files" {
            val service = newService()
            val namespaceId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            val sharedRoot = service.namespaceRoot(namespaceId)
            val caseRoot = service.caseRoot(namespaceId, caseId, createdAt)

            service.writeNew(sharedRoot, "shared.txt", "shared".toByteArray(), ExchangeScope.NAMESPACE)
            service.writeNew(caseRoot, "private.txt", "private".toByteArray(), ExchangeScope.CASE)

            val sharedFiles = service.listManifest(sharedRoot, ExchangeScope.NAMESPACE)

            sharedFiles.map { it.filename } shouldContainExactlyInAnyOrder listOf("shared.txt")
            // the case-private file must NOT leak into the namespace manifest
            sharedFiles.none { it.filename == "private.txt" } shouldBe true
        }

        "path traversal is rejected" {
            val service = newService()
            val root = service.caseRoot(UUID.randomUUID(), UUID.randomUUID(), createdAt)
            service.writeNew(root, "ok.txt", "ok".toByteArray(), ExchangeScope.CASE)

            shouldThrow<IllegalArgumentException> {
                service.readContent(root, "../escape.txt")
            }
            shouldThrow<IllegalArgumentException> {
                service.writeNew(root, "../escape.txt", "x".toByteArray(), ExchangeScope.CASE)
            }
        }

        "caseRoot is sharded by the case creation date (UTC YYYY/MM/DD)" {
            val service = newService()
            val caseId = UUID.randomUUID()

            val root = service.caseRoot(UUID.randomUUID(), caseId, Instant.parse("2025-12-09T23:30:00Z"))

            root.fileName.toString() shouldBe caseId.toString()
            root.parent.fileName.toString() shouldBe "09"
            root.parent.parent.fileName.toString() shouldBe "12"
            root.parent.parent.parent.fileName.toString() shouldBe "2025"
            root.parent.parent.parent.parent.fileName.toString() shouldBe "cases"
        }

        "isUploadAllowed accepts a whitelisted extension and rejects others (case-insensitive)" {
            val service = newService()

            service.isUploadAllowed("report.txt") shouldBe true
            service.isUploadAllowed("data/report.PDF") shouldBe true
            service.isUploadAllowed("malware.exe") shouldBe false
            service.isUploadAllowed("noextension") shouldBe false
        }

        "isUploadAllowed allows any extension when the allow-list is empty" {
            val service =
                ExchangeStorageService(
                    ExchangeStorageConfigProperties(
                        mountRoot = Files.createTempDirectory("exchange-test").toString(),
                        allowedUploadExtensions = emptySet(),
                    ),
                )

            service.isUploadAllowed("malware.exe") shouldBe true
        }
    })
