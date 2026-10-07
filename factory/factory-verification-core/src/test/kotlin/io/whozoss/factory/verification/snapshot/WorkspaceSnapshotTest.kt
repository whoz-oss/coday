package io.whozoss.factory.verification.snapshot

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Snapshot / diff tests. Real git repositories are created under the system temp
 * directory; only git is required, no database, no network.
 */
class WorkspaceSnapshotTest {

    // ----------------------------------------------------------- pure diff

    @Test
    fun `pure diff retains appeared or changed fingerprints only`() {
        val before = WorkspaceSnapshot.Snapshot(
            modified = mapOf("x" to "1", "stable" to "s"),
            untracked = emptyMap(),
        )
        val after = WorkspaceSnapshot.Snapshot(
            modified = mapOf("x" to "2", "stable" to "s"),
            untracked = mapOf("y" to "3"),
        )

        val delta = WorkspaceSnapshot.diff(before, after)
        assertEquals(listOf("x"), delta.modified)
        assertEquals(listOf("y"), delta.untracked)
        assertFalse(WorkspaceSnapshot.wroteNothing(delta))
    }

    @Test
    fun `diff of identical snapshots wrote nothing`() {
        val snapshot = WorkspaceSnapshot.Snapshot(modified = mapOf("x" to "1"), untracked = emptyMap())
        assertTrue(WorkspaceSnapshot.wroteNothing(WorkspaceSnapshot.diff(snapshot, snapshot)))
    }

    // ----------------------------------------------------------- git snapshot

    @Test
    fun `snapshot detects modified and untracked files`() {
        val dir = newGitRepo()
        Files.writeString(dir.resolve("a.txt"), "one")
        git(dir, "add", "a.txt")
        commit(dir, "init")

        val before = WorkspaceSnapshot.snapshot(dir)
        assertTrue(before.modified.isEmpty(), "clean repo should have no modified file: ${before.modified}")
        assertTrue(before.untracked.isEmpty(), "clean repo should have no untracked file: ${before.untracked}")

        Files.writeString(dir.resolve("a.txt"), "two")
        Files.writeString(dir.resolve("b.txt"), "new")

        val after = WorkspaceSnapshot.snapshot(dir)
        assertTrue(after.modified.containsKey("a.txt"))
        assertTrue(after.untracked.containsKey("b.txt"))

        val delta = WorkspaceSnapshot.diff(before, after)
        assertEquals(listOf("a.txt"), delta.modified)
        assertEquals(listOf("b.txt"), delta.untracked)
        assertFalse(WorkspaceSnapshot.wroteNothing(delta))
    }

    @Test
    fun `content change is detected even when path and length are unchanged`() {
        val dir = newGitRepo()
        Files.writeString(dir.resolve("a.txt"), "AAAA")
        git(dir, "add", "a.txt")
        commit(dir, "init")

        val before = WorkspaceSnapshot.snapshot(dir)
        Files.writeString(dir.resolve("a.txt"), "BBBB")

        val delta = WorkspaceSnapshot.diffSince(before, dir)
        assertEquals(listOf("a.txt"), delta.modified)
    }

    @Test
    fun `a settled working tree wrote nothing`() {
        val dir = newGitRepo()
        Files.writeString(dir.resolve("a.txt"), "one")
        git(dir, "add", "a.txt")
        commit(dir, "init")

        val before = WorkspaceSnapshot.snapshot(dir)
        assertTrue(WorkspaceSnapshot.wroteNothing(WorkspaceSnapshot.diffSince(before, dir)))
    }

    // ----------------------------------------------------------- fingerprints

    @Test
    fun `a disappeared file maps to the unreadable sentinel`() {
        val dir = Files.createTempDirectory("factory-snapshot-missing")
        assertEquals(WorkspaceSnapshot.UNREADABLE, WorkspaceSnapshot.contentFingerprint(dir, "not-there.txt"))
    }

    @Test
    fun `the fingerprint is the SHA-256 of the content`() {
        val dir = Files.createTempDirectory("factory-snapshot-hash")
        Files.writeString(dir.resolve("a.txt"), "hello")
        // sha256("hello")
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            WorkspaceSnapshot.contentFingerprint(dir, "a.txt"),
        )
    }

    // ----------------------------------------------------------- helpers

    private fun newGitRepo(): Path {
        val dir = Files.createTempDirectory("factory-snapshot-test")
        git(dir, "init", "-q")
        return dir
    }

    private fun commit(dir: Path, message: String) {
        git(dir, "-c", "user.email=test@example.com", "-c", "user.name=Test", "commit", "-q", "-m", message)
    }

    private fun git(dir: Path, vararg args: String): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(dir.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val code = process.waitFor()
        check(code == 0) { "git ${args.joinToString(" ")} failed ($code): $output" }
        return output
    }
}
