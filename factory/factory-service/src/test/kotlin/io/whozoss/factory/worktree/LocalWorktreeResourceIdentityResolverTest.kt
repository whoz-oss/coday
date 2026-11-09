package io.whozoss.factory.worktree

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LocalWorktreeResourceIdentityResolverTest {
    @TempDir lateinit var temp: Path

    private val resolver = LocalWorktreeResourceIdentityResolver()

    @Test
    fun `directory resolves to prefixed real path`() {
        val directory = Files.createDirectory(temp.resolve("worktree"))
        assertThat(resolver.resolve(directory))
            .isEqualTo("${LocalWorktreeResourceIdentityResolver.PREFIX}${directory.toRealPath()}")
    }

    @Test
    fun `symbolic link alias converges to same identity`() {
        val directory = Files.createDirectory(temp.resolve("target"))
        val alias = temp.resolve("alias")
        val linked = runCatching { Files.createSymbolicLink(alias, directory) }.isSuccess
        assumeTrue(linked, "symbolic links are unavailable on this filesystem")

        assertThat(resolver.resolve(alias)).isEqualTo(resolver.resolve(directory))
    }

    @Test
    fun `distinct directories have distinct identities`() {
        val first = Files.createDirectory(temp.resolve("first"))
        val second = Files.createDirectory(temp.resolve("second"))
        assertThat(resolver.resolve(first)).isNotEqualTo(resolver.resolve(second))
    }

    @Test
    fun `absent path is refused`() {
        assertThatThrownBy { resolver.resolve(temp.resolve("missing")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not exist")
    }

    @Test
    fun `regular file is refused`() {
        val file = Files.createFile(temp.resolve("file"))
        assertThatThrownBy { resolver.resolve(file) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("not a directory")
    }
}
