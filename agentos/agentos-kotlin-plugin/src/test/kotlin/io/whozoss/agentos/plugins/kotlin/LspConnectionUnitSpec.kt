package io.whozoss.agentos.plugins.kotlin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eclipse.lsp4j.ConfigurationItem
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val ownProcessId: Long = ProcessHandle.current().pid()

/** What the fake server saw of the handshake, and how it answers it. */
private class FakeLanguageServer(
    private val answerInitialize: Boolean = true,
) : LanguageServer {
    val initializations = CopyOnWriteArrayList<InitializeParams>()
    val shutdowns = CopyOnWriteArrayList<Boolean>()

    /** The client of the connection, as the server sees it: what it uses to make its own requests. */
    @Volatile var client: LanguageClient? = null

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        initializations.add(params)
        return when {
            answerInitialize -> CompletableFuture.completedFuture(InitializeResult(ServerCapabilities()))
            else -> CompletableFuture()
        }
    }

    override fun shutdown(): CompletableFuture<Any> {
        shutdowns.add(true)
        return CompletableFuture.completedFuture(null)
    }

    override fun exit() {
        shutdowns.add(true)
    }

    override fun getTextDocumentService(): TextDocumentService = IgnoringTextDocumentService()

    override fun getWorkspaceService(): WorkspaceService = IgnoringWorkspaceService()
}

/** The document notifications the handshake tests do not look at. */
private class IgnoringTextDocumentService : TextDocumentService {
    override fun didOpen(params: DidOpenTextDocumentParams) = Unit

    override fun didChange(params: DidChangeTextDocumentParams) = Unit

    override fun didClose(params: DidCloseTextDocumentParams) = Unit

    override fun didSave(params: DidSaveTextDocumentParams) = Unit
}

/** The workspace notifications the handshake tests do not look at. */
private class IgnoringWorkspaceService : WorkspaceService {
    override fun didChangeConfiguration(params: DidChangeConfigurationParams) = Unit

    override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) = Unit
}

/** A loopback port served by [fake] over LSP, standing for a running `intellij-server --multi-client`. */
private class FakeServerPort(
    fake: FakeLanguageServer,
) : AutoCloseable {
    private val listening = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).also { it.isDaemon = true } }
    val port: Int = listening.localPort

    init {
        executor.submit {
            runCatching {
                val accepted = listening.accept()
                val launcher = LSPLauncher.createServerLauncher(fake, accepted.getInputStream(), accepted.getOutputStream())
                fake.client = launcher.remoteProxy
                launcher.startListening()
            }
        }
    }

    override fun close() {
        runCatching { listening.close() }
        executor.shutdownNow()
    }
}

private fun endpointOn(port: Int): ServerEndpoint =
    ServerEndpoint(server = PublishedServer(port = port, processId = ownProcessId), launched = null)

class LspConnectionUnitSpec :
    StringSpec({
        "a connection should complete the LSP handshake with the project as root" {
            // given
            val fake = FakeLanguageServer()

            FakeServerPort(fake = fake).use { fakePort ->
                // when
                val connection = LspConnection(endpoint = endpointOn(port = fakePort.port), projectUri = "file:///project/")

                // then
                try {
                    fake.initializations.single().rootUri shouldBe "file:///project/"
                    connection.isAlive() shouldBe true
                } finally {
                    connection.close()
                }
            }
        }

        "the handshake should not name this service as the parent of the server, which does not own it" {
            // given
            val fake = FakeLanguageServer()

            FakeServerPort(fake = fake).use { fakePort ->
                // when
                val connection = LspConnection(endpoint = endpointOn(port = fakePort.port), projectUri = "file:///project/")

                // then
                try {
                    (fake.initializations.single().processId == null) shouldBe true
                } finally {
                    connection.close()
                }
            }
        }

        "closing a connection should hang up without shutting the shared server down" {
            // given
            val fake = FakeLanguageServer()

            FakeServerPort(fake = fake).use { fakePort ->
                val connection = LspConnection(endpoint = endpointOn(port = fakePort.port), projectUri = "file:///project/")

                // when
                connection.close()

                // then
                connection.isAlive() shouldBe false
                fake.shutdowns.size shouldBe 0
            }
        }

        "a connection should no longer be alive when the process of the server is gone" {
            // given
            val fake = FakeLanguageServer()

            FakeServerPort(fake = fake).use { fakePort ->
                // when
                val connection =
                    LspConnection(
                        endpoint =
                            ServerEndpoint(
                                server = PublishedServer(port = fakePort.port, processId = Int.MAX_VALUE.toLong() * 2),
                                launched = null,
                            ),
                        projectUri = "file:///project/",
                    )

                // then
                try {
                    connection.isAlive() shouldBe false
                } finally {
                    connection.close()
                }
            }
        }

        "a request of the server to its client, made while it works, should be answered and not fail" {
            // given
            val fake = FakeLanguageServer()

            FakeServerPort(fake = fake).use { fakePort ->
                val connection = LspConnection(endpoint = endpointOn(port = fakePort.port), projectUri = "file:///project/")

                // when
                val answer =
                    fake.client
                        ?.configuration(ConfigurationParams(listOf(ConfigurationItem(), ConfigurationItem())))
                        ?.get(5, TimeUnit.SECONDS)

                // then: crossed the wire as JSON, where the null items are read back as JsonNull
                try {
                    answer?.map { item -> item?.toString() ?: "null" } shouldBe listOf("null", "null")
                } finally {
                    connection.close()
                }
            }
        }

        "a port that refuses the connection should fail with a message naming the port" {
            // given
            val closedPort = ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }

            // when
            val error =
                shouldThrow<LspInitializationException> {
                    LspConnection(endpoint = endpointOn(port = closedPort), projectUri = "file:///project/")
                }

            // then
            error.message.orEmpty() shouldContain "port $closedPort"
            error.message.orEmpty() shouldContain "refused"
        }
    })
