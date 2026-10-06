package io.whozoss.factory.verification.oracle

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for owner-project and build-host resolution and effective-command building. */
class OracleCommandTest {

    private fun writeJson(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    private fun repoWithOwnerProject(): Path {
        val root = Files.createTempDirectory("oracle-command")
        writeJson(root.resolve("libs/foo/project.json"), "{\"name\":\"foo\"}")
        Files.createDirectories(root.resolve("libs/foo/src"))
        Files.writeString(root.resolve("libs/foo/src/x.ts"), "// x")
        return root
    }

    @Test
    fun `resolveOwnerProjects finds the nearest project json`() {
        val root = repoWithOwnerProject()
        Files.writeString(root.resolve("README.md"), "# root")
        val projects = OracleCommand.resolveOwnerProjects(listOf("libs/foo/src/x.ts", "README.md"), root)
        assertEquals(listOf("foo"), projects)
    }

    @Test
    fun `extractTarget reads short and long forms`() {
        assertEquals("build", OracleCommand.extractTarget("pnpm nx affected -t build"))
        assertEquals("type-check", OracleCommand.extractTarget("pnpm nx run-many --target=type-check --projects=a"))
        assertNull(OracleCommand.extractTarget("pnpm nx run foo:build"))
    }

    @Test
    fun `fixed scope returns the template unchanged`() {
        val root = Files.createTempDirectory("oracle-fixed")
        val result = OracleCommand.buildOracleCommand(
            OracleCommand.OracleCommandSpec(command = "./gradlew :agentos-service:build --rerun-tasks --console=plain"),
            files = listOf("anything"),
            repoRoot = root,
        )
        assertEquals(
            OracleCommand.OracleCommandResult.Command("./gradlew :agentos-service:build --rerun-tasks --console=plain"),
            result,
        )
    }

    @Test
    fun `filesArg with an empty list returns the template unchanged`() {
        val root = Files.createTempDirectory("oracle-empty-files")
        val result = OracleCommand.buildOracleCommand(
            OracleCommand.OracleCommandSpec(command = "pnpm nx affected -t frontend-test", filesArg = true),
            files = emptyList(),
            repoRoot = root,
        )
        assertEquals(OracleCommand.OracleCommandResult.Command("pnpm nx affected -t frontend-test"), result)
    }

    @Test
    fun `filesArg with files builds a run-many command over owner projects`() {
        val root = repoWithOwnerProject()
        val result = OracleCommand.buildOracleCommand(
            OracleCommand.OracleCommandSpec(command = "pnpm nx affected -t frontend-test", filesArg = true),
            files = listOf("libs/foo/src/x.ts"),
            repoRoot = root,
        )
        assertEquals(
            OracleCommand.OracleCommandResult.Command(
                "pnpm nx run-many --target=frontend-test --projects=foo --skip-nx-cache",
            ),
            result,
        )
    }

    @Test
    fun `buildHostArg without a host map yields the no-host sentinel`() {
        val root = repoWithOwnerProject()
        val result = OracleCommand.buildOracleCommand(
            OracleCommand.OracleCommandSpec(
                command = "pnpm nx run-many --target=build --skip-nx-cache",
                buildHostArg = true,
            ),
            files = listOf("libs/foo/src/x.ts"),
            repoRoot = root,
            environment = emptyMap(),
        )
        assertTrue(result is OracleCommand.OracleCommandResult.NoHost)
    }

    @Test
    fun `buildHostArg appends resolved hosts when the map is valid`() {
        val root = repoWithOwnerProject()
        writeJson(root.resolve("apps/hostapp/project.json"), "{\"targets\":{\"build\":{}}}")
        val result = OracleCommand.buildOracleCommand(
            OracleCommand.OracleCommandSpec(
                command = "pnpm nx run-many --target=build --skip-nx-cache",
                buildHostArg = true,
            ),
            files = listOf("libs/foo/src/x.ts"),
            repoRoot = root,
            environment = mapOf("FACTORY_FRONT_BUILD_HOST_MAP" to "{\"foo\":[\"hostapp\"]}"),
        )
        assertEquals(
            OracleCommand.OracleCommandResult.Command(
                "pnpm nx run-many --target=build --skip-nx-cache --projects=hostapp",
            ),
            result,
        )
    }

    @Test
    fun `resolveBuildHosts rejects malformed host maps`() {
        val root = Files.createTempDirectory("oracle-hosts")
        val result = OracleCommand.resolveBuildHosts(
            ownerProjects = listOf("foo"),
            repoRoot = root,
            environment = mapOf("FACTORY_FRONT_BUILD_HOST_MAP" to "not-json"),
        )
        assertTrue(result is OracleCommand.BuildHostResult.NoHost)
    }
}
