package io.whozoss.agentos.plugins.kotlin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.nio.file.Path
import java.util.concurrent.TimeUnit

private const val HOME = "/home/test"
private val userInstall = Path.of("$HOME/kotlin-lsp/server/bin/intellij-server")
private val customBinary = Path.of("/opt/lsp/intellij-server")
private val otherBinary = Path.of("/opt/other/intellij-server")
private val project = Path.of("/project")
private val otherProject = Path.of("/other-project")
private val oneHourAndASecond = TimeUnit.HOURS.toMillis(1) + 1_000

private fun liveConnection(): LanguageServerConnection =
    mockk<LanguageServerConnection>().also { connection ->
        every { connection.isAlive() } returns true
        every { connection.close() } returns Unit
    }

/** A pool whose servers are stubs, that records which binary and system path each connection used. */
private class RecordingPool {
    val started = mutableListOf<Path>()
    val systemPaths = mutableListOf<Path>()
    val connections = mutableListOf<LanguageServerConnection>()
    var now = 0L

    val pool =
        LspConnectionPool(
            resolver = KotlinLspBinaryResolver(env = { null }, userHome = HOME, isExecutable = { true }),
            userHome = HOME,
            clock = { now },
            connect = { serverBinary, _, systemPath ->
                started.add(serverBinary)
                systemPaths.add(systemPath)
                liveConnection().also { connections.add(it) }
            },
        )
}

class LspConnectionPoolUnitSpec :
    StringSpec({
        "the binary named by the integration is the one started" {
            // given
            val recording = RecordingPool()

            // when
            recording.pool.obtain(root = project, serverBinary = "$customBinary")

            // then
            recording.started shouldContainExactly listOf(customBinary)
        }

        "without a binary in the integration the user install is started" {
            // given
            val recording = RecordingPool()

            // when
            recording.pool.obtain(root = project)

            // then
            recording.started shouldContainExactly listOf(userInstall)
        }

        "a project asked twice with the same binary shares one server" {
            // given
            val recording = RecordingPool()

            // when
            val first = recording.pool.obtain(root = project, serverBinary = "$customBinary")
            val second = recording.pool.obtain(root = project, serverBinary = "$customBinary")

            // then
            recording.started.size shouldBe 1
            (first === second) shouldBe true
        }

        "two integrations on the same project with different binaries have a server each" {
            // given
            val recording = RecordingPool()

            // when
            recording.pool.obtain(root = project, serverBinary = "$customBinary")
            recording.pool.obtain(root = project, serverBinary = "$otherBinary")

            // then
            recording.started shouldContainExactly listOf(customBinary, otherBinary)
        }

        "two binaries on the same project do not share a system path, which has one server" {
            // given
            val recording = RecordingPool()

            // when
            recording.pool.obtain(root = project, serverBinary = "$customBinary")
            recording.pool.obtain(root = project, serverBinary = "$otherBinary")

            // then
            recording.systemPaths.distinct().size shouldBe 2
        }

        "the system path of a binary and a project is stable and under the user directory, outside the project" {
            // given
            val first = RecordingPool()
            val second = RecordingPool()

            // when
            first.pool.obtain(root = project, serverBinary = "$customBinary")
            second.pool.obtain(root = project, serverBinary = "$customBinary")

            // then
            first.systemPaths shouldContainExactly second.systemPaths
            first.systemPaths.single().startsWith(Path.of(HOME, ".coday", "agentos", "kotlin-lsp")) shouldBe true
            first.systemPaths.single().startsWith(project) shouldBe false
        }

        "a connection whose server is gone is replaced on the next call" {
            // given
            val recording = RecordingPool()
            val first = recording.pool.obtain(root = project)
            every { first.isAlive() } returns false

            // when
            val second = recording.pool.obtain(root = project)

            // then
            (second !== first) shouldBe true
            recording.started.size shouldBe 2
            verify { first.close() }
        }

        "a connection unused for more than an hour is hung up when another project is asked" {
            // given
            val recording = RecordingPool()
            val idle = recording.pool.obtain(root = project)
            recording.now += oneHourAndASecond

            // when
            recording.pool.obtain(root = otherProject)

            // then
            verify { idle.close() }
        }

        "a connection used again within the hour is kept, and the clock restarts from that use" {
            // given
            val recording = RecordingPool()
            val kept = recording.pool.obtain(root = project)
            recording.now += TimeUnit.MINUTES.toMillis(50)
            recording.pool.obtain(root = project)
            recording.now += TimeUnit.MINUTES.toMillis(50)

            // when
            recording.pool.obtain(root = otherProject)

            // then
            verify(exactly = 0) { kept.close() }
            recording.pool.obtain(root = project) shouldBe kept
        }

        "the connection that is asked for is never the one released as idle" {
            // given
            val recording = RecordingPool()
            val first = recording.pool.obtain(root = project)
            recording.now += oneHourAndASecond

            // when
            val again = recording.pool.obtain(root = project)

            // then
            again shouldBe first
            again shouldNotBe null
            verify(exactly = 0) { first.close() }
        }

        "closing the pool hangs up from every server" {
            // given
            val recording = RecordingPool()
            recording.pool.obtain(root = project)
            recording.pool.obtain(root = otherProject)

            // when
            recording.pool.closeAll()

            // then
            recording.connections.forEach { connection -> verify { connection.close() } }
        }
    })
