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

            shouldThrow<InvalidExchangePathException> {
                service.readContent(root, "../escape.txt")
            }
            shouldThrow<InvalidExchangePathException> {
                service.writeNew(root, "../escape.txt", "x".toByteArray(), ExchangeScope.CASE)
            }
        }

        "a blank relative path is rejected (would otherwise resolve to the scope root)" {
            val service = newService()
            val root = service.caseRoot(UUID.randomUUID(), UUID.randomUUID(), createdAt)
            service.writeNew(root, "ok.txt", "ok".toByteArray(), ExchangeScope.CASE)

            shouldThrow<InvalidExchangePathException> { service.delete(root, "") }
            shouldThrow<InvalidExchangePathException> { service.readContent(root, "   ") }
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

        "isUploadAllowed derives the extension from the leaf filename only" {
            val service = newService()
            // a dot in a parent segment must not be mistaken for the extension
            service.isUploadAllowed("v1.2/report") shouldBe false
            service.isUploadAllowed("v1.2/report.txt") shouldBe true
            // a backslash is also treated as a path separator
            service.isUploadAllowed("dir\\report.pdf") shouldBe true
        }

        "isUploadAllowed matches case-insensitively against an uppercase operator override" {
            val service =
                ExchangeStorageService(
                    ExchangeStorageConfigProperties(
                        mountRoot = Files.createTempDirectory("exchange-test").toString(),
                        allowedUploadExtensions = setOf("PDF", "DOCX"),
                    ),
                )

            service.isUploadAllowed("report.pdf") shouldBe true
            service.isUploadAllowed("notes.DOCX") shouldBe true
            service.isUploadAllowed("evil.exe") shouldBe false
        }

        "a path with a NUL byte is rejected as an invalid path (not surfaced as a server error)" {
            val service = newService()
            val root = service.caseRoot(UUID.randomUUID(), UUID.randomUUID(), createdAt)
            service.writeNew(root, "ok.txt", "ok".toByteArray(), ExchangeScope.CASE)

            shouldThrow<InvalidExchangePathException> {
                service.readContent(root, "foo\u0000.txt")
            }
        }

        "reads reject a file larger than the configured read limit" {
            val service =
                ExchangeStorageService(
                    ExchangeStorageConfigProperties(
                        mountRoot = Files.createTempDirectory("exchange-test").toString(),
                        readMaxSizeBytes = 4,
                    ),
                )
            val root = service.namespaceRoot(UUID.randomUUID())
            service.writeNew(root, "big.txt", "hello".toByteArray(), ExchangeScope.NAMESPACE)

            shouldThrow<ExchangeFileTooLargeException> { service.readContent(root, "big.txt") }
            shouldThrow<ExchangeFileTooLargeException> { service.readBytes(root, "big.txt") }
        }

        "listManifest lists a real file even if its name contains the former staging marker" {
            // Regression guard: upload staging now lives outside every scope root (under
            // <mountRoot>/.staging), so no name-based filter is applied and a real user file whose
            // name happens to contain the former marker substring is listed normally.
            val service = newService()
            val root = service.caseRoot(UUID.randomUUID(), UUID.randomUUID(), createdAt)
            service.writeNew(root, "notes.__exchange_staging__.md", "x".toByteArray(), ExchangeScope.CASE)

            service.listManifest(root, ExchangeScope.CASE).map { it.filename } shouldContainExactlyInAnyOrder
                listOf("notes.__exchange_staging__.md")
        }

        "listManifest skips the internal .staging subdirectory" {
            val service = newService()
            val root = service.caseRoot(UUID.randomUUID(), UUID.randomUUID(), createdAt)
            service.writeNew(root, "real.txt", "x".toByteArray(), ExchangeScope.CASE)
            // a leftover staging artifact under the internal .staging subdir must never surface
            Files.createDirectories(root.resolve(".staging"))
            Files.write(root.resolve(".staging").resolve("orphan.tmp"), "tmp".toByteArray())

            service.listManifest(root, ExchangeScope.CASE).map { it.filename } shouldContainExactlyInAnyOrder
                listOf("real.txt")
        }
    })
