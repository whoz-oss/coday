package io.whozoss.factory.worktree

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.whozoss.factory.proxy.AgentOsProxyClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentOsWorkspaceIdentityValidatorTest {
    @TempDir lateinit var temp: Path
    private val proxy = mockk<AgentOsProxyClient>()
    private val validator = AgentOsWorkspaceIdentityValidator(proxy)

    @Test
    fun `matching workspace including symlink alias is accepted and user is propagated`() {
        val target = Files.createDirectory(temp.resolve("target"))
        val alias = temp.resolve("alias")
        assumeTrue(runCatching { Files.createSymbolicLink(alias, target) }.isSuccess, "symbolic links unavailable")
        every { proxy.fetchWorkspaceChanges("case-1", "alice") } returns ready(alias)

        assertThat(validator.validate("case-1", target, "alice"))
            .isEqualTo(LocalWorktreeResourceIdentityResolver().resolve(target))
        verify(exactly = 1) { proxy.fetchWorkspaceChanges("case-1", "alice") }
    }

    @Test
    fun `divergent workspace is refused`() {
        val expected = Files.createDirectory(temp.resolve("expected"))
        val other = Files.createDirectory(temp.resolve("other"))
        every { proxy.fetchWorkspaceChanges(any(), any()) } returns ready(other)
        assertThatThrownBy { validator.validate("case", expected, "user") }
            .isInstanceOf(AgentOsWorkspaceIdentityValidationException::class.java)
            .hasMessageContaining("does not match")
    }

    @Test
    fun `absent path unequipped and nonready workspace are refused`() {
        val expected = Files.createDirectory(temp.resolve("expected"))
        listOf(
            mapOf<String, Any?>("equipped" to true, "status" to "READY"),
            mapOf<String, Any?>("equipped" to false, "status" to "READY", "path" to expected.toString()),
            mapOf<String, Any?>("equipped" to true, "status" to "PROVISIONING", "path" to expected.toString()),
            mapOf<String, Any?>("equipped" to true, "status" to "READY", "path" to temp.resolve("missing").toString()),
        ).forEachIndexed { index, response ->
            every { proxy.fetchWorkspaceChanges("case-$index", "user") } returns response
            assertThatThrownBy { validator.validate("case-$index", expected, "user") }
                .isInstanceOf(AgentOsWorkspaceIdentityValidationException::class.java)
        }
    }

    @Test
    fun `remote failure and missing workspace are refused`() {
        val expected = Files.createDirectory(temp.resolve("expected"))
        every { proxy.fetchWorkspaceChanges("error", "user") } throws IllegalStateException("down")
        every { proxy.fetchWorkspaceChanges("missing", "user") } returns null
        assertThatThrownBy { validator.validate("error", expected, "user") }
            .isInstanceOf(AgentOsWorkspaceIdentityValidationException::class.java)
            .hasMessageContaining("could not be read")
        assertThatThrownBy { validator.validate("missing", expected, "user") }
            .isInstanceOf(AgentOsWorkspaceIdentityValidationException::class.java)
            .hasMessageContaining("not found")
    }

    private fun ready(path: Path): Map<String, Any?> = mapOf(
        "equipped" to true,
        "status" to "READY",
        "path" to path.toString(),
        "changes" to mapOf("files" to emptyList<Any>()),
    )
}
