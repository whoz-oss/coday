package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.whozoss.agentos.sdk.auth.ToolCategory
import io.whozoss.agentos.sdk.tool.StandardTool

class PermissionManifestBuilderSpec : StringSpec() {

    private val userId = "user-123"
    private val caseId = "case-456"

    private fun buildTool(
        name: String,
        category: ToolCategory = ToolCategory.READ_ONLY,
    ): StandardTool<*> = mockk {
        every { this@mockk.name } returns name
        every { this@mockk.category } returns category
    }

    private fun buildBuilder(
        availableToolNames: Set<String>,
    ): PermissionManifestBuilder {
        val authorizationService = mockk<AuthorizationService> {
            every { getAvailableTools(userId, caseId, any()) } returns availableToolNames
        }
        return PermissionManifestBuilder(authorizationService)
    }

    init {

        // -------------------------------------------------------------------------
        // AC-F: Allowed + forbidden tools correctly listed
        // -------------------------------------------------------------------------

        "manifest lists allowed and forbidden tools correctly" {
            val readTool = buildTool("readFile", ToolCategory.READ_ONLY)
            val writeTool = buildTool("writeFile", ToolCategory.WRITE)
            val destructiveTool = buildTool("deleteFile", ToolCategory.DESTRUCTIVE)

            val builder = buildBuilder(setOf("readFile", "writeFile"))
            val manifest = builder.buildManifest(userId, caseId, listOf(readTool, writeTool, destructiveTool))

            manifest shouldContain "readFile"
            manifest shouldContain "writeFile"
            manifest shouldContain "deleteFile"
            manifest shouldContain "Allowed tools:"
            manifest shouldContain "Forbidden tools (do NOT call these):"
            manifest shouldContain "DESTRUCTIVE"
            manifest shouldContain "Requires ADMIN role"
        }

        // -------------------------------------------------------------------------
        // AC-F: Permissive mode — all tools allowed, no Forbidden section
        // -------------------------------------------------------------------------

        "permissive mode lists all tools as allowed with no Forbidden section" {
            val readTool = buildTool("readFile", ToolCategory.READ_ONLY)
            val writeTool = buildTool("writeFile", ToolCategory.WRITE)
            val destructiveTool = buildTool("deleteFile", ToolCategory.DESTRUCTIVE)

            val allTools = listOf(readTool, writeTool, destructiveTool)
            val builder = buildBuilder(setOf("readFile", "writeFile", "deleteFile"))
            val manifest = builder.buildManifest(userId, caseId, allTools)

            manifest shouldContain "readFile"
            manifest shouldContain "writeFile"
            manifest shouldContain "deleteFile"
            manifest shouldContain "Allowed tools:"
            manifest shouldNotContain "Forbidden tools"
        }

        // -------------------------------------------------------------------------
        // AC-F: All tools blocked — none allowed
        // -------------------------------------------------------------------------

        "all tools blocked when getAvailableTools returns empty set" {
            val readTool = buildTool("readFile", ToolCategory.READ_ONLY)
            val writeTool = buildTool("writeFile", ToolCategory.WRITE)

            val builder = buildBuilder(emptySet())
            val manifest = builder.buildManifest(userId, caseId, listOf(readTool, writeTool))

            manifest shouldContain "Allowed tools:"
            manifest shouldContain "Forbidden tools (do NOT call these):"
            manifest shouldNotContain "- readFile (READ_ONLY)\n" // not in allowed
            // Both should appear in forbidden
            manifest shouldContain "readFile (READ_ONLY) — Requires VIEWER role"
            manifest shouldContain "writeFile (WRITE) — Requires MEMBER role"
        }

        // -------------------------------------------------------------------------
        // AC-F: Format contains expected sections
        // -------------------------------------------------------------------------

        "manifest format contains required sections" {
            val tool = buildTool("testTool", ToolCategory.READ_ONLY)
            val forbiddenTool = buildTool("adminTool", ToolCategory.ADMIN)

            val builder = buildBuilder(setOf("testTool"))
            val manifest = builder.buildManifest(userId, caseId, listOf(tool, forbiddenTool))

            manifest shouldContain "## Tool Permissions"
            manifest shouldContain "You MUST respect these permissions. Never attempt to call a forbidden tool."
            manifest shouldContain "### Allowed tools:"
            manifest shouldContain "### Forbidden tools (do NOT call these):"
        }

        // -------------------------------------------------------------------------
        // AC-F: Tool names and categories appear in manifest
        // -------------------------------------------------------------------------

        "tool names and categories appear in manifest" {
            val readTool = buildTool("listFiles", ToolCategory.READ_ONLY)
            val writeTool = buildTool("editFile", ToolCategory.WRITE)
            val adminTool = buildTool("manageUsers", ToolCategory.ADMIN)

            val builder = buildBuilder(setOf("listFiles", "editFile"))
            val manifest = builder.buildManifest(userId, caseId, listOf(readTool, writeTool, adminTool))

            manifest shouldContain "listFiles (READ_ONLY)"
            manifest shouldContain "editFile (WRITE)"
            manifest shouldContain "manageUsers (ADMIN)"
        }

        // -------------------------------------------------------------------------
        // AC-F: userId and internal roles do NOT appear in manifest (NFR6)
        // -------------------------------------------------------------------------

        "userId and internal roles do not appear in manifest" {
            val tool = buildTool("readFile", ToolCategory.READ_ONLY)
            val forbidden = buildTool("deleteFile", ToolCategory.DESTRUCTIVE)

            val builder = buildBuilder(setOf("readFile"))
            val manifest = builder.buildManifest(userId, caseId, listOf(tool, forbidden))

            manifest shouldNotContain userId
            manifest shouldNotContain caseId
            manifest shouldNotContain "VIEWER" // user's actual role should not appear
            // Note: ADMIN appears as minimumNamespaceRole for DESTRUCTIVE, which is the required role, not the user's role
        }

        // -------------------------------------------------------------------------
        // AC-F: Empty tool collection returns empty string
        // -------------------------------------------------------------------------

        "empty tool collection returns empty string" {
            val builder = buildBuilder(emptySet())
            val manifest = builder.buildManifest(userId, caseId, emptyList())

            manifest shouldBe ""
        }

        // -------------------------------------------------------------------------
        // Additional edge cases
        // -------------------------------------------------------------------------

        "manifest with only allowed tools has no Forbidden section" {
            val tool = buildTool("readFile", ToolCategory.READ_ONLY)

            val builder = buildBuilder(setOf("readFile"))
            val manifest = builder.buildManifest(userId, caseId, listOf(tool))

            manifest shouldContain "Allowed tools:"
            manifest shouldContain "readFile (READ_ONLY)"
            manifest shouldNotContain "Forbidden tools"
        }

        "manifest with 10 tools — 6 READ_ONLY, 3 WRITE, 1 DESTRUCTIVE (AC-D scenario)" {
            val readTools = (1..6).map { buildTool("readTool$it", ToolCategory.READ_ONLY) }
            val writeTools = (1..3).map { buildTool("writeTool$it", ToolCategory.WRITE) }
            val destructiveTool = buildTool("destructiveTool1", ToolCategory.DESTRUCTIVE)
            val allTools = readTools + writeTools + listOf(destructiveTool)

            val allowedNames = (readTools + writeTools).map { it.name }.toSet()
            val builder = buildBuilder(allowedNames)
            val manifest = builder.buildManifest(userId, caseId, allTools)

            // 9 allowed tools
            readTools.forEach { manifest shouldContain "${it.name} (READ_ONLY)" }
            writeTools.forEach { manifest shouldContain "${it.name} (WRITE)" }
            // 1 forbidden tool
            manifest shouldContain "destructiveTool1 (DESTRUCTIVE) — Requires ADMIN role"
        }
    }
}
