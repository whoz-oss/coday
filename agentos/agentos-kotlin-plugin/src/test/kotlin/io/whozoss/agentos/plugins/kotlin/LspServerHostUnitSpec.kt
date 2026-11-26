package io.whozoss.agentos.plugins.kotlin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private val currentProcessId: Long = ProcessHandle.current().pid()

/** A port on the loopback interface that accepts connections, standing for the port of a running server. */
private fun listeningPort(): ServerSocket = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))

private fun <T> inTempDirectory(block: (Path) -> T): T {
    val directory = Files.createTempDirectory("agentos-lsp-host")
    return try {
        block(directory)
    } finally {
        directory.toFile().deleteRecursively()
    }
}

/** A shell script standing for the server binary: [body] runs with the `--port-file` argument in `$PORT_FILE`. */
private fun fakeServerBinary(
    directory: Path,
    body: String,
): Path {
    val script = directory.resolve("fake-intellij-server")
    Files.writeString(
        script,
        """
        #!/bin/sh
        for argument in "${'$'}@"; do
          case "${'$'}argument" in
            --port-file=*) PORT_FILE="${'$'}{argument#--port-file=}" ;;
          esac
        done
        echo "${'$'}@" > "${'$'}0.args"
        $body
        """.trimIndent(),
    )
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
    return script
}

private fun writePortFile(
    systemPath: Path,
    port: Int,
    processId: Long = currentProcessId,
) {
    Files.createDirectories(systemPath)
    Files.writeString(systemPath.resolve(PublishedServer.PORT_FILE_NAME), "$port\n$processId\nILS-test\n")
}

class LspServerHostUnitSpec :
    StringSpec({
        "no port file means no running server" {
            inTempDirectory { directory ->
                LspServerHost(serverBinary = directory.resolve("none"), systemPath = directory).findRunning().shouldBeNull()
            }
        }

        "a port file of a live process whose port accepts connections is a running server" {
            inTempDirectory { directory ->
                listeningPort().use { server ->
                    // given
                    writePortFile(systemPath = directory, port = server.localPort)

                    // when
                    val running = LspServerHost(serverBinary = directory.resolve("none"), systemPath = directory).findRunning()

                    // then
                    running shouldBe PublishedServer(port = server.localPort, processId = currentProcessId)
                }
            }
        }

        "a port file left by a process that is gone is not a running server" {
            inTempDirectory { directory ->
                listeningPort().use { server ->
                    // given
                    writePortFile(systemPath = directory, port = server.localPort, processId = Int.MAX_VALUE.toLong() * 2)

                    // when / then
                    LspServerHost(serverBinary = directory.resolve("none"), systemPath = directory).findRunning().shouldBeNull()
                }
            }
        }

        "a port file whose port refuses connections is not a running server" {
            inTempDirectory { directory ->
                // given
                val closedPort = listeningPort().use { it.localPort }
                writePortFile(systemPath = directory, port = closedPort)

                // when / then
                LspServerHost(serverBinary = directory.resolve("none"), systemPath = directory).findRunning().shouldBeNull()
            }
        }

        "a running server is reused without starting anything, even when the binary does not exist" {
            inTempDirectory { directory ->
                listeningPort().use { server ->
                    // given
                    writePortFile(systemPath = directory, port = server.localPort)

                    // when
                    val endpoint = LspServerHost(serverBinary = directory.resolve("missing"), systemPath = directory).obtain()

                    // then
                    endpoint.server.port shouldBe server.localPort
                    endpoint.launched.shouldBeNull()
                }
            }
        }

        "a server is started as a shared, loopback server that stops by itself, and its port is read from its port file" {
            inTempDirectory { directory ->
                listeningPort().use { server ->
                    // given
                    val systemPath = directory.resolve("system")
                    val binary =
                        fakeServerBinary(
                            directory = directory,
                            body = "printf '%s\\n%s\\n%s\\n' ${server.localPort} $currentProcessId ILS-test > \"\$PORT_FILE\"\nsleep 30",
                        )

                    // when
                    val endpoint = LspServerHost(serverBinary = binary, systemPath = systemPath).obtain()

                    // then
                    try {
                        endpoint.server shouldBe PublishedServer(port = server.localPort, processId = currentProcessId)
                        endpoint.launched.shouldNotBeNull()
                        val arguments = Files.readString(directory.resolve("fake-intellij-server.args"))
                        arguments shouldContain "--multi-client"
                        arguments shouldContain "--system-path=$systemPath"
                        arguments shouldContain "--port-file=${systemPath.resolve(PublishedServer.PORT_FILE_NAME)}"
                        arguments shouldContain "--socket=127.0.0.1:0"
                        arguments shouldContain "--idle-timeout=1h"
                        arguments.contains("--stdio") shouldBe false
                    } finally {
                        endpoint.launched?.let { LspServerHost.stopTree(process = it) }
                    }
                }
            }
        }

        "a server that exits, refused by the lock of another one, makes the error carry what it printed" {
            inTempDirectory { directory ->
                // given
                val binary =
                    fakeServerBinary(
                        directory = directory,
                        body = "echo 'Another language server already uses the instance directory /x.'\nexit 1",
                    )
                val host = LspServerHost(serverBinary = binary, systemPath = directory.resolve("system"), exitGraceMillis = 300)

                // when
                val error = shouldThrow<LspInitializationException> { host.obtain() }

                // then
                error.message.orEmpty() shouldContain "exited with code 1"
                error.message.orEmpty() shouldContain "Another language server already uses the instance directory"
            }
        }

        "a server that loses the race against another start joins the winner once it publishes its port" {
            inTempDirectory { directory ->
                listeningPort().use { winner ->
                    // given: our server is refused by the lock at once, the other one publishes a little later
                    val systemPath = directory.resolve("system")
                    val binary = fakeServerBinary(directory = directory, body = "echo 'locked'\nexit 1")
                    val publisher =
                        Thread {
                            Thread.sleep(400)
                            writePortFile(systemPath = systemPath, port = winner.localPort)
                        }
                    publisher.start()

                    // when
                    val endpoint = LspServerHost(serverBinary = binary, systemPath = systemPath, exitGraceMillis = 5_000).obtain()

                    // then
                    publisher.join()
                    endpoint.server.port shouldBe winner.localPort
                    endpoint.launched.shouldBeNull()
                }
            }
        }

        "a server that never publishes its port is stopped and the error says so" {
            inTempDirectory { directory ->
                // given
                val binary = fakeServerBinary(directory = directory, body = "echo 'indexing forever'\nsleep 30")
                val host = LspServerHost(serverBinary = binary, systemPath = directory.resolve("system"), startTimeoutMillis = 500)

                // when
                val error = shouldThrow<LspInitializationException> { host.obtain() }

                // then
                error.message.orEmpty() shouldContain "did not publish its port"
                error.message.orEmpty() shouldContain "indexing forever"
            }
        }

        "a binary that is not executable is refused when a server has to be started" {
            inTempDirectory { directory ->
                // given
                val binary = Files.writeString(directory.resolve("plain-file"), "not a program")

                // when / then
                shouldThrow<IllegalArgumentException> {
                    LspServerHost(serverBinary = binary, systemPath = directory.resolve("system")).obtain()
                }.message.orEmpty() shouldContain "not executable"
            }
        }
    })
