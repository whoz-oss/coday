package io.whozoss.agentos.auth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.agentos.sdk.auth.ToolCategory
import io.whozoss.agentos.sdk.tool.StandardTool
import java.io.File

class ToolExecutionGuardSpec : StringSpec() {

    private val callerId = "user-123"
    private val caseId = "case-456"
    private val namespaceId = "ns-789"
    private val toolName = "testTool"
    private val toolArgs = """{"key":"value"}"""
    private val toolOutput = "tool executed successfully"

    private fun buildTool(
        name: String = toolName,
        category: ToolCategory = ToolCategory.READ_ONLY,
        executeResult: String = toolOutput,
        throwOnExecute: Exception? = null,
    ): StandardTool<*> = mockk {
        every { this@mockk.name } returns name
        every { this@mockk.category } returns category
        if (throwOnExecute != null) {
            every { executeWithJson(any()) } throws throwOnExecute
        } else {
            every { executeWithJson(any()) } returns executeResult
        }
    }

    private fun buildGuard(
        canExecute: Boolean = true,
        throwOnCheck: Exception? = null,
    ): Pair<ToolExecutionGuard, PermissionAuditService> {
        val authService = mockk<AuthorizationService>()
        val auditService = mockk<PermissionAuditService>(relaxed = true)

        if (throwOnCheck != null) {
            every { authService.canExecuteTool(any(), any(), any(), any()) } throws throwOnCheck
        } else {
            every { authService.canExecuteTool(any(), any(), any(), any()) } returns canExecute
        }

        return ToolExecutionGuard(authService, auditService) to auditService
    }

    init {
        // =====================================================================
        // AC-H: Authorized execution -> Success + logGranted
        // =====================================================================

        "authorized execution returns Success and calls logGranted" {
            val tool = buildTool()
            val (guard, auditService) = buildGuard(canExecute = true)

            val result = guard.executeWithPermissionCheck(tool, toolArgs, callerId, caseId, namespaceId)

            result.shouldBeInstanceOf<GuardedToolResult.Success>()
            result.toolName shouldBe toolName
            result.output shouldBe toolOutput
            verify(exactly = 1) { auditService.logGranted(callerId, namespaceId, toolName, caseId) }
            verify(exactly = 0) { auditService.logDenied(any(), any(), any(), any()) }
        }

        // =====================================================================
        // AC-H: Denied execution -> Denied + logDenied + tool NOT executed
        // =====================================================================

        "denied execution returns Denied, calls logDenied and does NOT execute tool" {
            val tool = buildTool()
            val (guard, auditService) = buildGuard(canExecute = false)

            val result = guard.executeWithPermissionCheck(tool, toolArgs, callerId, caseId, namespaceId)

            result.shouldBeInstanceOf<GuardedToolResult.Denied>()
            result.toolName shouldBe toolName
            verify(exactly = 1) { auditService.logDenied(callerId, namespaceId, toolName, caseId) }
            verify(exactly = 0) { auditService.logGranted(any(), any(), any(), any()) }
            verify(exactly = 0) { tool.executeWithJson(any()) }
        }

        // =====================================================================
        // AC-H: Execution error -> Error + logGranted (permission was ok)
        // =====================================================================

        "execution error returns Error and calls logGranted (permission was granted)" {
            val tool = buildTool(throwOnExecute = RuntimeException("tool crash"))
            val (guard, auditService) = buildGuard(canExecute = true)

            val result = guard.executeWithPermissionCheck(tool, toolArgs, callerId, caseId, namespaceId)

            result.shouldBeInstanceOf<GuardedToolResult.Error>()
            result.toolName shouldBe toolName
            result.error shouldBe "tool crash"
            verify(exactly = 1) { auditService.logGranted(callerId, namespaceId, toolName, caseId) }
        }

        // =====================================================================
        // AC-H: Default category READ_ONLY used when tool.category not overridden
        // =====================================================================

        "default category READ_ONLY is passed to authorizationService" {
            val tool = buildTool(category = ToolCategory.READ_ONLY)
            val authService = mockk<AuthorizationService>()
            val auditService = mockk<PermissionAuditService>(relaxed = true)
            every { authService.canExecuteTool(any(), any(), any(), any()) } returns true
            val guard = ToolExecutionGuard(authService, auditService)

            guard.executeWithPermissionCheck(tool, toolArgs, callerId, caseId, namespaceId)

            verify(exactly = 1) { authService.canExecuteTool(callerId, caseId, toolName, ToolCategory.READ_ONLY) }
        }

        // =====================================================================
        // NFR9: Fail-closed when canExecuteTool throws
        // =====================================================================

        "fail-closed when canExecuteTool throws exception" {
            val tool = buildTool()
            val (guard, auditService) = buildGuard(throwOnCheck = RuntimeException("auth service down"))

            val result = guard.executeWithPermissionCheck(tool, toolArgs, callerId, caseId, namespaceId)

            result.shouldBeInstanceOf<GuardedToolResult.Denied>()
            verify(exactly = 1) { auditService.logDenied(callerId, namespaceId, toolName, caseId) }
            verify(exactly = 0) { tool.executeWithJson(any()) }
        }

        // =====================================================================
        // AC-H: Correct audit calls in each scenario
        // =====================================================================

        "audit service receives correct parameters for granted execution" {
            val tool = buildTool(name = "specificTool", category = ToolCategory.WRITE)
            val (guard, auditService) = buildGuard(canExecute = true)

            guard.executeWithPermissionCheck(tool, toolArgs, "caller-abc", "case-def", "ns-ghi")

            verify(exactly = 1) { auditService.logGranted("caller-abc", "ns-ghi", "specificTool", "case-def") }
        }

        // =====================================================================
        // AC-G: Lint/grep test — no direct executeWithJson in AgentSimple/AgentAdvanced
        // =====================================================================

        "no direct executeWithJson or executeWithAny calls in AgentSimple.kt or AgentAdvanced.kt" {
            val agentDir = File("src/main/kotlin/io/whozoss/agentos/agent")
            val agentSimple = File(agentDir, "AgentSimple.kt")
            val agentAdvanced = File(agentDir, "AgentAdvanced.kt")

            val filesToCheck = listOf(agentSimple, agentAdvanced).filter { it.exists() }

            filesToCheck.forEach { file ->
                val content = file.readText()
                val lines = content.lines()

                lines.forEachIndexed { index, line ->
                    // Skip comments and import statements
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("import")) {
                        return@forEachIndexed
                    }

                    val hasDirectExecuteWithJson = line.contains("executeWithJson(") &&
                        !line.contains("guard.executeWithPermissionCheck")
                    val hasDirectExecuteWithAny = line.contains("executeWithAny(") &&
                        !line.contains("guard.executeWithPermissionCheck")

                    if (hasDirectExecuteWithJson) {
                        throw AssertionError(
                            "Direct executeWithJson() call found in ${file.name} at line ${index + 1}: " +
                                "'${trimmed}'. All tool execution must go through ToolExecutionGuard.",
                        )
                    }
                    if (hasDirectExecuteWithAny) {
                        throw AssertionError(
                            "Direct executeWithAny() call found in ${file.name} at line ${index + 1}: " +
                                "'${trimmed}'. All tool execution must go through ToolExecutionGuard.",
                        )
                    }
                }
            }
        }
    }
}
