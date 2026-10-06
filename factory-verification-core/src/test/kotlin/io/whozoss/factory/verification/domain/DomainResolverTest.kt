package io.whozoss.factory.verification.domain

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure domain-resolution tests (no process is spawned). */
class DomainResolverTest {

    private val defaultRoot: Path = Path.of("/tmp/factory-repo")

    @Test
    fun `resolves the default back domain`() {
        val back = DomainResolver.back(emptyMap(), defaultRoot)
        assertEquals(1, back.oracles.size)
        val oracle = back.oracles.single()
        assertEquals("build", oracle.name)
        assertEquals(DomainResolver.DEFAULT_BACK_COMMAND, oracle.command)
        assertEquals(defaultRoot.resolve("agentos"), oracle.cwd)
    }

    @Test
    fun `resolves the default front domain`() {
        val front = DomainResolver.front(emptyMap(), defaultRoot)
        assertEquals(listOf("build", "tests"), front.oracles.map { it.name })

        val build = front.oracles[0]
        assertEquals(DomainResolver.DEFAULT_FRONT_BUILD_COMMAND, build.command)
        assertEquals(defaultRoot, build.cwd)
        assertTrue(build.buildHostArg)

        val tests = front.oracles[1]
        assertEquals("pnpm nx affected -t frontend-test", tests.command)
        assertTrue(tests.filesArg)
    }

    @Test
    fun `resolve returns both domains`() {
        val domains = DomainResolver.resolve(emptyMap(), defaultRoot)
        assertEquals(setOf("back", "front"), domains.keys)
    }

    @Test
    fun `FACTORY_ROOT overrides the repository root`() {
        val env = mapOf(DomainResolver.ENV_ROOT to "/other/root")
        val domains = DomainResolver.resolve(env, defaultRoot)
        assertEquals(Path.of("/other/root"), domains["back"]!!.oracles.single().cwd.parent)
        assertEquals(Path.of("/other/root"), domains["front"]!!.oracles[0].cwd)
    }

    @Test
    fun `back command and cwd overrides are honoured`() {
        val env = mapOf(
            DomainResolver.ENV_COMMAND_BACK to "./gradlew :custom:build",
            DomainResolver.ENV_CWD_BACK to "/custom/agentos",
        )
        val oracle = DomainResolver.back(env, defaultRoot).oracles.single()
        assertEquals("./gradlew :custom:build", oracle.command)
        assertEquals(Path.of("/custom/agentos"), oracle.cwd)
    }

    @Test
    fun `front build and tests overrides are honoured`() {
        val env = mapOf(
            DomainResolver.ENV_COMMAND_FRONT_BUILD to "pnpm nx build custom --skip-nx-cache",
            DomainResolver.ENV_COMMAND_FRONT to "pnpm nx run custom:unit",
            DomainResolver.ENV_CWD_FRONT to "/custom/front",
        )
        val front = DomainResolver.front(env, defaultRoot)
        assertEquals("pnpm nx build custom --skip-nx-cache", front.oracles[0].command)
        assertEquals("pnpm nx run custom:unit", front.oracles[1].command)
        assertEquals(Path.of("/custom/front"), front.oracles[0].cwd)
        assertEquals(Path.of("/custom/front"), front.oracles[1].cwd)
    }

    @Test
    fun `FACTORY_FRONT_TEST_TARGET changes the default tests command`() {
        val env = mapOf(DomainResolver.ENV_FRONT_TEST_TARGET to "unit")
        val tests = DomainResolver.front(env, defaultRoot).oracles[1]
        assertEquals("pnpm nx affected -t unit", tests.command)
    }
}
