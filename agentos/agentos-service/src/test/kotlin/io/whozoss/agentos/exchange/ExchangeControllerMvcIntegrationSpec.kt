package io.whozoss.agentos.exchange

import com.ninjasquad.springmockk.MockkBean
import io.kotest.core.spec.style.StringSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import io.whozoss.agentos.caseFlow.Case
import io.whozoss.agentos.caseFlow.CaseService
import io.whozoss.agentos.permissions.Action
import io.whozoss.agentos.permissions.EntityType
import io.whozoss.agentos.permissions.PermissionService
import io.whozoss.agentos.persistence.neo4j.EmbeddedNeo4jTestConfiguration
import io.whozoss.agentos.sdk.api.exchange.ExchangeDirectoryEntry
import io.whozoss.agentos.sdk.api.exchange.ExchangeFileContent
import io.whozoss.agentos.sdk.api.exchange.ExchangeFileEntry
import io.whozoss.agentos.sdk.api.exchange.ExchangeScope
import io.whozoss.agentos.sdk.entity.EntityMetadata
import io.whozoss.agentos.user.User
import io.whozoss.agentos.user.UserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.io.IOException
import java.io.OutputStream
import java.time.Instant
import java.util.UUID

/**
 * MVC-layer test for [ExchangeController].
 *
 * Mirrors [io.whozoss.agentos.security.declarative.MethodSecurityIntegrationSpec]: the
 * permissive `test`-profile [PermissionService] / [UserService] beans are replaced with
 * `@MockkBean(relaxed = true)` so each test drives `hasPermission` per call. That lets us
 * exercise the full security + MVC stack:
 * - server-computed capability (`READ_WRITE` vs `READ`) embedded in every manifest,
 * - `@HideOnAccessDenied` translating a denied READ into 404 (hide existence),
 * - multipart upload wiring,
 * - the storage-error mapping (`FileExistsException` -> 409, missing path -> 404, bad path -> 400).
 *
 * [ExchangeStorageService] and [CaseService] are mocked so the controller is tested in
 * isolation from the filesystem and the graph store.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test", "embedded-neo4j")
@Import(EmbeddedNeo4jTestConfiguration::class)
class ExchangeControllerMvcIntegrationSpec : StringSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired lateinit var mockMvc: MockMvc

    @MockkBean(relaxed = true) lateinit var userService: UserService

    @MockkBean(relaxed = true) lateinit var permissionService: PermissionService

    @MockkBean(relaxed = true) lateinit var caseService: CaseService

    @MockkBean(relaxed = true) lateinit var exchangeStorageService: ExchangeStorageService

    private val user = User(
        metadata = EntityMetadata(id = UUID.randomUUID()),
        externalId = "member@example.com",
        email = "member@example.com",
        isAdmin = false,
    )
    private val userId = user.id.toString()

    /** Stub the case lookup so the controller can resolve the namespace root. */
    private fun stubCase(caseId: UUID, namespaceId: UUID = UUID.randomUUID()) {
        every { userService.getCurrentUser() } returns user
        every { caseService.findById(caseId) } returns
            Case(metadata = EntityMetadata(id = caseId), namespaceId = namespaceId)
    }

    private fun entry(path: String, scope: ExchangeScope) = ExchangeFileEntry(
        path = path,
        filename = path.substringAfterLast('/'),
        size = 4L,
        lastModified = Instant.parse("2026-01-01T00:00:00Z"),
        mimeType = "text/plain",
        scope = scope,
        etag = "abc",
    )

    init {

        "GET case directory returns a bounded JSON page and the caller capability" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns false
            every { exchangeStorageService.listDirectory(any(), "docs", 0, 500) } returns
                (listOf(ExchangeDirectoryEntry(path = "docs/report.txt", name = "report.txt", directory = false)) to 700)

            mockMvc.perform(get("/api/cases/$caseId/files/directory").param("path", "docs").param("page", "-5").param("size", "99999"))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", "application/json"))
                .andExpect(jsonPath("$.entries[0].path").value("docs/report.txt"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.pageSize").value(500))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.capability").value("READ"))
            verify { exchangeStorageService.listDirectory(any(), "docs", 0, 500) }
        }

        "GET namespace directory returns no content when READ is denied" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns false
            mockMvc.perform(get("/api/namespaces/$namespaceId/files/directory"))
                .andExpect(status().isNotFound)
        }

        "GET case directory maps a vanished subdirectory to 404" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { exchangeStorageService.listDirectory(any(), "vanished", 0, 200) } throws NoSuchFileException("vanished")
            mockMvc.perform(get("/api/cases/$caseId/files/directory").param("path", "vanished"))
                .andExpect(status().isNotFound)
        }

        "GET case directory maps storage failures without exposing server paths" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { exchangeStorageService.listDirectory(any(), "docs", 0, 200) } throws IOException("/srv/private/exchange/docs")
            val result = mockMvc.perform(get("/api/cases/$caseId/files/directory").param("path", "docs"))
                .andExpect(status().isBadRequest)
                .andReturn()
            result.resolvedException?.message shouldBe "Invalid file operation"
        }

        // -------------------------------------------------------------------------
        // Case manifest — capability is server-computed and fail-closed
        // -------------------------------------------------------------------------

        "GET case manifest returns files and READ_WRITE capability for a writer" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.listManifest(any(), ExchangeScope.CASE) } returns
                listOf(entry("report.txt", ExchangeScope.CASE))

            mockMvc.perform(get("/api/cases/$caseId/files/manifest"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.capability").value("READ_WRITE"))
                .andExpect(jsonPath("$.files[0].path").value("report.txt"))
                .andExpect(jsonPath("$.files[0].scope").value("CASE"))
        }

        "GET case manifest returns READ capability when caller lacks write" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns false
            every { exchangeStorageService.listManifest(any(), ExchangeScope.CASE) } returns emptyList()

            mockMvc.perform(get("/api/cases/$caseId/files/manifest"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.capability").value("READ"))
        }

        "GET case manifest returns 404 when READ is denied (HideOnAccessDenied)" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns false

            mockMvc.perform(get("/api/cases/$caseId/files/manifest"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // Case content / download — storage-error mapping (B3 hardening)
        // -------------------------------------------------------------------------

        "GET case content and download hide files when READ is denied" {
            val caseId = UUID.randomUUID()
            val path = "private-${UUID.randomUUID()}.txt"
            val sentinel = "sentinel-${UUID.randomUUID()}"
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns false
            every { exchangeStorageService.readContent(any(), path) } returns
                ExchangeFileContent(content = sentinel, etag = "test", mimeType = "text/plain", size = sentinel.length.toLong())
            every { exchangeStorageService.readBytes(any(), path) } returns (sentinel.toByteArray() to "text/plain")

            for (endpoint in listOf("content", "download")) {
                val response = mockMvc.perform(get("/api/cases/$caseId/files/$endpoint").param("path", path))
                    .andExpect(status().isNotFound)
                    .andReturn().response
                response.contentAsString.contains(sentinel) shouldBe false
                response.contentAsString.contains(path) shouldBe false
                response.getHeader("Content-Disposition") shouldBe null
            }
            verify(exactly = 2) { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) }
            verify(exactly = 0) { exchangeStorageService.readContent(any(), path) }
            verify(exactly = 0) { exchangeStorageService.readBytes(any(), path) }
        }

        "GET case content returns the file content" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { exchangeStorageService.readContent(any(), "report.txt") } returns
                ExchangeFileContent(content = "hello", etag = "abc", mimeType = "text/plain", size = 5L)

            mockMvc.perform(get("/api/cases/$caseId/files/content").param("path", "report.txt"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.content").value("hello"))
        }

        "GET case content returns 404 when the file is missing" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { exchangeStorageService.readContent(any(), "gone.txt") } throws
                NoSuchFileException("gone.txt")

            mockMvc.perform(get("/api/cases/$caseId/files/content").param("path", "gone.txt"))
                .andExpect(status().isNotFound)
        }

        "GET case content returns 400 for a traversal path" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { exchangeStorageService.readContent(any(), "../escape.txt") } throws
                InvalidExchangePathException("Invalid path: path traversal not allowed (../escape.txt)")

            mockMvc.perform(get("/api/cases/$caseId/files/content").param("path", "../escape.txt"))
                .andExpect(status().isBadRequest)
        }

        "GET case content returns 422 when the file exceeds the read size limit" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { exchangeStorageService.readContent(any(), "huge.csv") } throws
                ExchangeFileTooLargeException("File is too large to read")

            mockMvc.perform(get("/api/cases/$caseId/files/content").param("path", "huge.csv"))
                .andExpect(status().isUnprocessableEntity)
        }

        "GET case download streams bytes with a sanitized Content-Disposition filename" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { exchangeStorageService.readBytes(any(), "nested/report.txt") } returns
                ("data".toByteArray() to "text/plain")

            mockMvc.perform(get("/api/cases/$caseId/files/download").param("path", "nested/report.txt"))
                .andExpect(status().isOk)
                // path component stripped — header carries only the leaf name
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"report.txt\""))
        }

        // -------------------------------------------------------------------------
        // Case upload (multipart) — write gate + 409 / 400 mapping
        // -------------------------------------------------------------------------

        "POST case file (multipart) creates and returns the entry" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed(any()) } returns true
            every { exchangeStorageService.writeNew(any(), "report.txt", any(), ExchangeScope.CASE) } returns
                entry("report.txt", ExchangeScope.CASE)

            val file = MockMultipartFile("file", "report.txt", "text/plain", "data".toByteArray())

            mockMvc.perform(multipart("/api/cases/$caseId/files").file(file))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.path").value("report.txt"))
                .andExpect(jsonPath("$.scope").value("CASE"))
        }

        "POST case file returns 409 when the target already exists" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed(any()) } returns true
            every { exchangeStorageService.writeNew(any(), "report.txt", any(), ExchangeScope.CASE) } throws
                FileExistsException("File already exists: report.txt")

            val file = MockMultipartFile("file", "report.txt", "text/plain", "data".toByteArray())

            mockMvc.perform(multipart("/api/cases/$caseId/files").file(file))
                .andExpect(status().isConflict)
        }

        "POST case file returns 409 without leaking the absolute path on a raw FileAlreadyExistsException" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed(any()) } returns true
            // e.g. createDirectories on a parent segment that is an existing file: the raw NIO exception's
            // message is the absolute server path, which must not reach the 409 response.
            val leakyPath = "/srv/exchange/$caseId/report.txt"
            every { exchangeStorageService.writeNew(any(), "report.txt", any(), ExchangeScope.CASE) } throws
                FileAlreadyExistsException(leakyPath)

            val file = MockMultipartFile("file", "report.txt", "text/plain", "data".toByteArray())

            val result = mockMvc.perform(multipart("/api/cases/$caseId/files").file(file))
                .andExpect(status().isConflict)
                .andReturn()
            // the mapped 409 must carry a generic message, not the raw absolute server path
            // (which server.error.include-message=always would otherwise render into the body).
            result.resolvedException?.message shouldBe "File already exists"
        }

        "POST case file returns 403 when WRITE is denied" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns false

            val file = MockMultipartFile("file", "report.txt", "text/plain", "data".toByteArray())

            // POST is not @HideOnAccessDenied, so a denied write surfaces as 403 (not 404).
            mockMvc.perform(multipart("/api/cases/$caseId/files").file(file))
                .andExpect(status().isForbidden)
        }

        "POST case file returns 400 when the extension is not allowed" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed(any()) } returns false

            val file = MockMultipartFile("file", "malware.exe", "application/octet-stream", "data".toByteArray())

            mockMvc.perform(multipart("/api/cases/$caseId/files").file(file))
                .andExpect(status().isBadRequest)
        }

        // -------------------------------------------------------------------------
        // Case delete
        // -------------------------------------------------------------------------

        "DELETE case file returns 403 and does not delete when WRITE is denied" {
            val caseId = UUID.randomUUID()
            val path = "private-${UUID.randomUUID()}.txt"
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns false

            mockMvc.perform(delete("/api/cases/$caseId/files").param("path", path))
                .andExpect(status().isForbidden)
            verify(exactly = 1) { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) }
            verify(exactly = 0) { exchangeStorageService.delete(any(), path) }
        }

        "DELETE case file returns a success body" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.delete(any(), "report.txt") } returns Unit

            mockMvc.perform(delete("/api/cases/$caseId/files").param("path", "report.txt"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
        }

        "DELETE case file returns 404 when the target is missing" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.delete(any(), "gone.txt") } throws NoSuchFileException("gone.txt")

            mockMvc.perform(delete("/api/cases/$caseId/files").param("path", "gone.txt"))
                .andExpect(status().isNotFound)
        }

        "DELETE case file returns 400 without leaking the absolute path on a raw IOException" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            // deleting a non-empty directory throws DirectoryNotEmptyException, whose message is the
            // absolute server path; it must not reach the 400 body (server.error.include-message=always).
            val leakyPath = "/srv/exchange/$caseId/reports"
            every { exchangeStorageService.delete(any(), "reports") } throws DirectoryNotEmptyException(leakyPath)

            val result = mockMvc.perform(delete("/api/cases/$caseId/files").param("path", "reports"))
                .andExpect(status().isBadRequest)
                .andReturn()
            result.resolvedException?.message shouldBe "Invalid file operation"
        }

        // -------------------------------------------------------------------------
        // Folders: upload into a directory, create, delete, ZIP archive (case and namespace scopes)
        // -------------------------------------------------------------------------

        "POST case file with a directory stores the file inside that folder" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed(any()) } returns true
            every { exchangeStorageService.writeNew(any(), "docs/specs/report.txt", any(), ExchangeScope.CASE) } returns
                entry("docs/specs/report.txt", ExchangeScope.CASE)

            val file = MockMultipartFile("file", "report.txt", "text/plain", "data".toByteArray())

            // surrounding slashes and blanks of the directory are not part of the stored path
            mockMvc.perform(multipart("/api/cases/$caseId/files").file(file).param("directory", " /docs/specs/ "))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.path").value("docs/specs/report.txt"))
            verify { exchangeStorageService.writeNew(any(), "docs/specs/report.txt", any(), ExchangeScope.CASE) }
        }

        "POST namespace file with a directory stores the file inside that folder" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed(any()) } returns true
            every { exchangeStorageService.writeNew(any(), "docs/doc.md", any(), ExchangeScope.NAMESPACE) } returns
                entry("docs/doc.md", ExchangeScope.NAMESPACE)

            val file = MockMultipartFile("file", "doc.md", "text/markdown", "data".toByteArray())

            mockMvc.perform(multipart("/api/namespaces/$namespaceId/files").file(file).param("directory", "docs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.path").value("docs/doc.md"))
        }

        "POST case file checks the extension on the file name, wherever the directory is" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed("docs/malware.exe") } returns false

            val file = MockMultipartFile("file", "malware.exe", "application/octet-stream", "data".toByteArray())

            mockMvc.perform(multipart("/api/cases/$caseId/files").file(file).param("directory", "docs"))
                .andExpect(status().isBadRequest)
            verify(exactly = 0) { exchangeStorageService.writeNew(any(), any(), any(), any()) }
        }

        "POST case directory creates the folder" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.createDirectory(any(), "docs/drafts") } returns
                ExchangeDirectoryEntry(path = "docs/drafts", name = "drafts", directory = true)

            mockMvc.perform(post("/api/cases/$caseId/files/directory").param("path", "docs/drafts"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.path").value("docs/drafts"))
                .andExpect(jsonPath("$.directory").value(true))
        }

        "POST case directory maps a conflict to 409 and an invalid path to 400" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.createDirectory(any(), "docs") } throws FileExistsException("Already exists: docs")
            every { exchangeStorageService.createDirectory(any(), "../escape") } throws
                InvalidExchangePathException("Invalid path: path traversal not allowed (../escape)")

            mockMvc.perform(post("/api/cases/$caseId/files/directory").param("path", "docs"))
                .andExpect(status().isConflict)
            mockMvc.perform(post("/api/cases/$caseId/files/directory").param("path", "../escape"))
                .andExpect(status().isBadRequest)
        }

        "POST case directory returns 403 and creates nothing when WRITE is denied" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns false

            mockMvc.perform(post("/api/cases/$caseId/files/directory").param("path", "docs"))
                .andExpect(status().isForbidden)
            verify(exactly = 0) { exchangeStorageService.createDirectory(any(), any()) }
        }

        "POST namespace directory creates the folder for an admin and is 403 for a member" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.createDirectory(any(), "docs") } returns
                ExchangeDirectoryEntry(path = "docs", name = "docs", directory = true)

            mockMvc.perform(post("/api/namespaces/$namespaceId/files/directory").param("path", "docs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.directory").value(true))

            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns false
            mockMvc.perform(post("/api/namespaces/$namespaceId/files/directory").param("path", "other"))
                .andExpect(status().isForbidden)
        }

        "DELETE case directory returns a success body" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.deleteDirectory(any(), "docs") } returns Unit

            mockMvc.perform(delete("/api/cases/$caseId/files/directory").param("path", "docs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
            verify { exchangeStorageService.deleteDirectory(any(), "docs") }
        }

        "DELETE case directory maps missing to 404, not a directory or blank to 400, protected entries to 409" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.deleteDirectory(any(), "gone") } throws NoSuchFileException("/srv/private/gone")
            every { exchangeStorageService.deleteDirectory(any(), "file.txt") } throws NotDirectoryException("/srv/private/file.txt")
            every { exchangeStorageService.deleteDirectory(any(), " ") } throws InvalidExchangePathException("Invalid path: path must not be blank")
            every { exchangeStorageService.deleteDirectory(any(), "repo") } throws
                ReservedEntryConflictException("Folder contains protected entries: /srv/private/repo")

            mockMvc.perform(delete("/api/cases/$caseId/files/directory").param("path", "gone"))
                .andExpect(status().isNotFound)
            val notDirectory = mockMvc.perform(delete("/api/cases/$caseId/files/directory").param("path", "file.txt"))
                .andExpect(status().isBadRequest)
                .andReturn()
            notDirectory.resolvedException?.message shouldBe "Not a directory"
            mockMvc.perform(delete("/api/cases/$caseId/files/directory").param("path", " "))
                .andExpect(status().isBadRequest)
            val reserved = mockMvc.perform(delete("/api/cases/$caseId/files/directory").param("path", "repo"))
                .andExpect(status().isConflict)
                .andReturn()
            // generic message: the server path of the storage exception must not reach the response
            reserved.resolvedException?.message shouldBe "Folder contains protected entries"
        }

        "DELETE case directory returns 403 and deletes nothing when WRITE is denied" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.WRITE) } returns false

            mockMvc.perform(delete("/api/cases/$caseId/files/directory").param("path", "docs"))
                .andExpect(status().isForbidden)
            verify(exactly = 0) { exchangeStorageService.deleteDirectory(any(), any()) }
        }

        "DELETE namespace directory returns a success body for an admin and is 403 for a member" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.deleteDirectory(any(), "docs") } returns Unit

            mockMvc.perform(delete("/api/namespaces/$namespaceId/files/directory").param("path", "docs"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))

            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns false
            mockMvc.perform(delete("/api/namespaces/$namespaceId/files/directory").param("path", "docs"))
                .andExpect(status().isForbidden)
            verify(exactly = 1) { exchangeStorageService.deleteDirectory(any(), any()) }
        }

        "GET case archive writes a zip synchronously, named after the folder" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { exchangeStorageService.writeArchive(any(), "docs/specs", any()) } answers {
                thirdArg<OutputStream>().write("zip-bytes".toByteArray())
            }

            // not an async request: the archive is written on the request thread, so the servlet async timeout
            // cannot cut a large one
            val result = mockMvc.perform(get("/api/cases/$caseId/files/archive").param("path", "docs/specs"))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"specs.zip\""))
                .andReturn()
            result.request.isAsyncStarted shouldBe false
            result.response.contentAsByteArray.decodeToString() shouldBe "zip-bytes"
            verify { exchangeStorageService.assertArchivable(any(), "docs/specs") }
            verify { exchangeStorageService.writeArchive(any(), "docs/specs", any()) }
        }

        "GET case archive of the root is named case-files.zip" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true

            mockMvc.perform(get("/api/cases/$caseId/files/archive"))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"case-files.zip\""))
            verify { exchangeStorageService.writeArchive(any(), "", any()) }
        }

        "GET case archive reports a missing folder or a file as an error before writing anything" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns true
            every { exchangeStorageService.assertArchivable(any(), "gone") } throws NoSuchFileException("/srv/private/gone")
            every { exchangeStorageService.assertArchivable(any(), "file.txt") } throws NotDirectoryException("/srv/private/file.txt")

            val missing = mockMvc.perform(get("/api/cases/$caseId/files/archive").param("path", "gone"))
                .andExpect(status().isNotFound)
                .andReturn()
            missing.response.getHeader("Content-Disposition") shouldBe null
            mockMvc.perform(get("/api/cases/$caseId/files/archive").param("path", "file.txt"))
                .andExpect(status().isBadRequest)
            verify(exactly = 0) { exchangeStorageService.writeArchive(any(), any(), any()) }
        }

        "GET case archive returns 404 and archives nothing when READ is denied" {
            val caseId = UUID.randomUUID()
            stubCase(caseId)
            every { permissionService.hasPermission(userId, EntityType.CASE, caseId.toString(), Action.READ) } returns false

            mockMvc.perform(get("/api/cases/$caseId/files/archive"))
                .andExpect(status().isNotFound)
            verify(exactly = 0) { exchangeStorageService.assertArchivable(any(), any()) }
            verify(exactly = 0) { exchangeStorageService.writeArchive(any(), any(), any()) }
        }

        "GET namespace archive writes a zip, named namespace-files.zip at the root" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true

            mockMvc.perform(get("/api/namespaces/$namespaceId/files/archive"))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"namespace-files.zip\""))
        }

        "GET namespace archive returns 404 when READ is denied" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns false

            mockMvc.perform(get("/api/namespaces/$namespaceId/files/archive").param("path", "docs"))
                .andExpect(status().isNotFound)
        }

        // -------------------------------------------------------------------------
        // Namespace manifest — READ for a member, READ_WRITE for an admin, 404 when denied
        // -------------------------------------------------------------------------

        "GET namespace manifest returns READ capability for a member" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns false
            every { exchangeStorageService.listManifest(any(), ExchangeScope.NAMESPACE) } returns
                listOf(entry("shared.txt", ExchangeScope.NAMESPACE))

            mockMvc.perform(get("/api/namespaces/$namespaceId/files/manifest"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.capability").value("READ"))
                .andExpect(jsonPath("$.files[0].scope").value("NAMESPACE"))
        }

        "GET namespace manifest returns READ_WRITE capability for a namespace admin" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.listManifest(any(), ExchangeScope.NAMESPACE) } returns emptyList()

            mockMvc.perform(get("/api/namespaces/$namespaceId/files/manifest"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.capability").value("READ_WRITE"))
        }

        "GET namespace manifest returns 404 when READ is denied (HideOnAccessDenied)" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns false

            mockMvc.perform(get("/api/namespaces/$namespaceId/files/manifest"))
                .andExpect(status().isNotFound)
        }

        "GET namespace content and download hide files when READ is denied" {
            val namespaceId = UUID.randomUUID()
            val path = "private-${UUID.randomUUID()}.txt"
            val sentinel = "sentinel-${UUID.randomUUID()}"
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns false
            every { exchangeStorageService.readContent(any(), path) } returns
                ExchangeFileContent(content = sentinel, etag = "test", mimeType = "text/plain", size = sentinel.length.toLong())
            every { exchangeStorageService.readBytes(any(), path) } returns (sentinel.toByteArray() to "text/plain")

            for (endpoint in listOf("content", "download")) {
                val response = mockMvc.perform(get("/api/namespaces/$namespaceId/files/$endpoint").param("path", path))
                    .andExpect(status().isNotFound)
                    .andReturn().response
                response.contentAsString.contains(sentinel) shouldBe false
                response.contentAsString.contains(path) shouldBe false
                response.getHeader("Content-Disposition") shouldBe null
            }
            verify(exactly = 2) { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ) }
            verify(exactly = 0) { exchangeStorageService.readContent(any(), path) }
            verify(exactly = 0) { exchangeStorageService.readBytes(any(), path) }
        }

        "GET namespace download streams bytes" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.READ) } returns true
            every { exchangeStorageService.readBytes(any(), "shared.txt") } returns ("data".toByteArray() to "text/plain")

            mockMvc.perform(get("/api/namespaces/$namespaceId/files/download").param("path", "shared.txt"))
                .andExpect(status().isOk)
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"shared.txt\""))
        }

        // -------------------------------------------------------------------------
        // Namespace upload / delete — WRITE (= namespace admin); a member is read-only → 403
        // -------------------------------------------------------------------------

        "POST namespace file (multipart) creates and returns the entry for an admin" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed(any()) } returns true
            every { exchangeStorageService.writeNew(any(), "doc.md", any(), ExchangeScope.NAMESPACE) } returns
                entry("doc.md", ExchangeScope.NAMESPACE)

            val file = MockMultipartFile("file", "doc.md", "text/markdown", "data".toByteArray())

            mockMvc.perform(multipart("/api/namespaces/$namespaceId/files").file(file))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.path").value("doc.md"))
                .andExpect(jsonPath("$.scope").value("NAMESPACE"))
        }

        "POST namespace file returns 403 for a namespace member (WRITE denied)" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns false

            val file = MockMultipartFile("file", "doc.md", "text/markdown", "data".toByteArray())

            mockMvc.perform(multipart("/api/namespaces/$namespaceId/files").file(file))
                .andExpect(status().isForbidden)
        }

        "POST namespace file returns 409 when the target already exists" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed(any()) } returns true
            every { exchangeStorageService.writeNew(any(), "doc.md", any(), ExchangeScope.NAMESPACE) } throws
                FileExistsException("File already exists: doc.md")

            val file = MockMultipartFile("file", "doc.md", "text/markdown", "data".toByteArray())

            mockMvc.perform(multipart("/api/namespaces/$namespaceId/files").file(file))
                .andExpect(status().isConflict)
        }

        "POST namespace file returns 400 when the extension is not allowed" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.isUploadAllowed(any()) } returns false

            val file = MockMultipartFile("file", "malware.exe", "application/octet-stream", "data".toByteArray())

            mockMvc.perform(multipart("/api/namespaces/$namespaceId/files").file(file))
                .andExpect(status().isBadRequest)
        }

        "DELETE namespace file returns a success body for an admin" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns true
            every { exchangeStorageService.delete(any(), "shared.md") } returns Unit

            mockMvc.perform(delete("/api/namespaces/$namespaceId/files").param("path", "shared.md"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.success").value(true))
        }

        "DELETE namespace file returns 403 for a namespace member (WRITE denied)" {
            val namespaceId = UUID.randomUUID()
            every { userService.getCurrentUser() } returns user
            every { permissionService.hasPermission(userId, EntityType.NAMESPACE, namespaceId.toString(), Action.WRITE) } returns false

            mockMvc.perform(delete("/api/namespaces/$namespaceId/files").param("path", "shared.md"))
                .andExpect(status().isForbidden)
        }
    }
}
