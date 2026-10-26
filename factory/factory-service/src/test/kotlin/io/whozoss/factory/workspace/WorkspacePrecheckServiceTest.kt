package io.whozoss.factory.workspace

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.persistence.TenantScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pure unit tests of the read-only workspace pre-check (Lot F).
 *
 * They pin the contract: each missing prerequisite fails with its stable,
 * actionable code, no configuration is ever mutated, and an unavailable agent
 * read API is reported as unverified rather than assumed satisfied.
 */
class WorkspacePrecheckServiceTest {

    private val scope = TenantScope("org", "ws")
    private val namespace = "ns-1"

    private val readPort = mockk<WorkspacePrecheckPort>()
    private val contextPort = mockk<WorkspaceContextPort>()

    private fun service(properties: WorkspaceProperties = WorkspaceProperties()): WorkspacePrecheckService =
        WorkspacePrecheckService(readPort, contextPort, properties)

    private fun stubNamespaceAccessible() {
        every { readPort.isNamespaceAccessible(namespace, any()) } returns true
    }

    private fun stubGit(state: NamespaceGitState?) {
        every { readPort.namespaceGitState(namespace, any()) } returns state
    }

    private fun stubWorkstream(resolvable: Boolean = true) {
        every { contextPort.isWorkstreamResolvable(scope) } returns resolvable
    }

    private fun assertCode(block: () -> Unit, code: String) {
        assertThatThrownBy(block)
            .isInstanceOf(WorkspacePrecheckException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", code)
    }

    @Test
    fun `an inaccessible namespace fails with NAMESPACE_INACCESSIBLE`() {
        every { readPort.isNamespaceAccessible(namespace, any()) } returns false

        assertCode({ service().precheck(scope, namespace) }, WorkspacePrecheckCodes.NAMESPACE_INACCESSIBLE)
    }

    @Test
    fun `provisioning disabled fails with GIT_WORKSPACES_DISABLED`() {
        stubNamespaceAccessible()

        assertCode(
            { service(WorkspaceProperties(gitWorkspacesEnabled = false)).precheck(scope, namespace) },
            WorkspacePrecheckCodes.GIT_WORKSPACES_DISABLED,
        )
    }

    @Test
    fun `a missing Git association endpoint fails with GIT_WORKSPACES_DISABLED`() {
        stubNamespaceAccessible()
        stubGit(null)

        assertCode({ service().precheck(scope, namespace) }, WorkspacePrecheckCodes.GIT_WORKSPACES_DISABLED)
    }

    @Test
    fun `an unassociated namespace fails with GIT_CONFIG_MISSING`() {
        stubNamespaceAccessible()
        stubGit(NamespaceGitState(gitAvailable = true, associated = false))

        assertCode({ service().precheck(scope, namespace) }, WorkspacePrecheckCodes.GIT_CONFIG_MISSING)
    }

    @Test
    fun `a FAILED checkout fails with GIT_CONFIG_MISSING and the failure reason`() {
        stubNamespaceAccessible()
        stubGit(NamespaceGitState(gitAvailable = true, associated = true, checkoutStatus = "FAILED", failureReason = "auth denied"))

        assertThatThrownBy { service().precheck(scope, namespace) }
            .isInstanceOf(WorkspacePrecheckException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", WorkspacePrecheckCodes.GIT_CONFIG_MISSING)
            .hasMessageContaining("auth denied")
    }

    @Test
    fun `auto worktree disabled fails with AUTO_WORKTREE_DISABLED`() {
        stubNamespaceAccessible()
        stubGit(NamespaceGitState(gitAvailable = true, associated = true, checkoutStatus = "READY"))

        assertCode(
            { service(WorkspaceProperties(autoWorktreeForRootCases = false)).precheck(scope, namespace) },
            WorkspacePrecheckCodes.AUTO_WORKTREE_DISABLED,
        )
    }

    @Test
    fun `an unresolvable workstream fails with WORKSTREAM_UNRESOLVABLE`() {
        stubNamespaceAccessible()
        stubGit(NamespaceGitState(gitAvailable = true, associated = true, checkoutStatus = "READY"))
        stubWorkstream(resolvable = false)

        assertCode({ service().precheck(scope, namespace) }, WorkspacePrecheckCodes.WORKSTREAM_UNRESOLVABLE)
    }

    @Test
    fun `a missing required agent fails with REQUIRED_AGENTS_MISSING`() {
        stubNamespaceAccessible()
        stubGit(NamespaceGitState(gitAvailable = true, associated = true, checkoutStatus = "READY"))
        stubWorkstream()
        every { readPort.availableAgents(namespace, any()) } returns listOf("architect")

        assertCode(
            { service().precheck(scope, namespace, requiredAgents = listOf("architect", "reviewer")) },
            WorkspacePrecheckCodes.REQUIRED_AGENTS_MISSING,
        )
    }

    @Test
    fun `an unavailable agent read API is reported as unverified, never assumed satisfied`() {
        stubNamespaceAccessible()
        stubGit(NamespaceGitState(gitAvailable = true, associated = true, checkoutStatus = "READY"))
        stubWorkstream()
        every { readPort.availableAgents(namespace, any()) } returns null

        val report = service().precheck(scope, namespace, requiredAgents = listOf("architect"))

        assertThat(report.enabled).isTrue
        assertThat(report.unverified).contains("agents")
        assertThat(report.verified).contains("namespace", "gitWorkspaces", "gitConfiguration", "autoWorktree", "workstream")
    }

    @Test
    fun `a satisfied pre-check returns the verified report`() {
        stubNamespaceAccessible()
        stubGit(NamespaceGitState(gitAvailable = true, associated = true, checkoutStatus = "READY"))
        stubWorkstream()
        every { readPort.availableAgents(namespace, any()) } returns listOf("architect")

        val report = service().precheck(scope, namespace, requiredAgents = listOf("architect"))

        assertThat(report.enabled).isTrue
        assertThat(report.unverified).isEmpty()
        assertThat(report.verified).contains("namespace", "agents")
    }

    @Test
    fun `a disabled pre-check consults no API and mutates nothing`() {
        val report = service(WorkspaceProperties(enabled = false)).precheck(scope, namespace)

        assertThat(report.enabled).isFalse
        verify(exactly = 0) { readPort.isNamespaceAccessible(any(), any()) }
        verify(exactly = 0) { readPort.namespaceGitState(any(), any()) }
        verify(exactly = 0) { contextPort.isWorkstreamResolvable(any()) }
    }
}
