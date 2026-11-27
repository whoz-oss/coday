package io.whozoss.agentos.plugins.kotlin

import mu.KLogging
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.FileOperationsWorkspaceCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.RenameCapabilities
import org.eclipse.lsp4j.ResourceOperationKind
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceEditCapabilities
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageServer
import java.io.IOException
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.readText

/**
 * A client connection to a language server over the loopback TCP port it published (see [LspServerHost]).
 *
 * Performs the LSP handshake (initialize + initialized) right after connecting, bounded by [INITIALIZE_TIMEOUT_SECONDS].
 * When it fails, times out or is interrupted, the connection is dropped and [LspInitializationException] is thrown:
 * the next call starts again. A server this plugin started for this very connection is stopped with it; one that was
 * already running, and may serve others, is left alone.
 *
 * Closing a connection only hangs up. The server is shared and stops by itself when the last client left and its idle
 * timeout passed, so no `shutdown` or `exit` is ever sent: they would stop it for everyone.
 *
 * @param endpoint where the server listens, and the process started for it, if any
 * @param projectUri file URI of the project root
 */
internal class LspConnection(
    private val endpoint: ServerEndpoint,
    private val projectUri: String,
) : LanguageServerConnection {
    private val client: LspLanguageClient = LspLanguageClient()

    private val listenerExecutor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "kotlin-lsp-listener").also { it.isDaemon = true }
        }

    private val socket: Socket = connectedSocket(endpoint = endpoint)

    private val launcher: Launcher<LanguageServer> =
        LSPLauncher.createClientLauncher(client, socket.getInputStream(), socket.getOutputStream(), listenerExecutor) { it }

    /** Done when the server hung up: the connection is then of no more use. */
    private val listening: Future<Void> = launcher.startListening()

    override val server: LanguageServer = launcher.remoteProxy

    /** uri -> (version, contentHash) of the open documents. */
    private val openDocuments: MutableMap<String, OpenDocument> = mutableMapOf()

    private data class OpenDocument(
        val version: Int,
        val contentHash: Int,
    )

    init {
        handshake()
    }

    override fun isAlive(): Boolean =
        !socket.isClosed && !listening.isDone && LspServerHost.isProcessAlive(processId = endpoint.server.processId)

    @Synchronized
    override fun syncFile(filePath: String): String {
        val uri = Path.of(filePath).toUri().toString()
        val content = Path.of(filePath).readText()
        val contentHash = content.hashCode()
        val existing = openDocuments[uri]
        when {
            existing == null -> {
                val item =
                    TextDocumentItem().apply {
                        this.uri = uri
                        languageId = KOTLIN_LANGUAGE_ID
                        version = INITIAL_VERSION
                        text = content
                    }
                server.textDocumentService.didOpen(DidOpenTextDocumentParams(item))
                openDocuments[uri] = OpenDocument(version = INITIAL_VERSION, contentHash = contentHash)
                logger.debug { "LSP didOpen: $uri" }
            }

            existing.contentHash != contentHash -> {
                val nextVersion = existing.version + 1
                val changeEvent = TextDocumentContentChangeEvent().apply { text = content }
                server.textDocumentService.didChange(
                    DidChangeTextDocumentParams(VersionedTextDocumentIdentifier(uri, nextVersion), listOf(changeEvent)),
                )
                openDocuments[uri] = OpenDocument(version = nextVersion, contentHash = contentHash)
                logger.debug { "LSP didChange: $uri v$nextVersion" }
            }

            else -> {
                logger.debug { "LSP already in sync: $uri" }
            }
        }
        return uri
    }

    @Synchronized
    override fun closeFile(filePath: String) {
        val uri = Path.of(filePath).toUri().toString()
        if (openDocuments.remove(uri) != null) {
            server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            logger.debug { "LSP didClose: $uri" }
        }
    }

    override fun close() {
        logger.info {
            "Hanging up from Kotlin LSP server pid=${endpoint.server.processId} port=${endpoint.server.port} project=$projectUri"
        }
        runCatching { socket.close() }
            .onFailure { logger.warn { "Error closing the Kotlin LSP socket: ${it.message}" } }
        listenerExecutor.shutdownNow()
    }

    /** Drops the connection after a failed handshake, and the server too when it was started for this connection. */
    private fun abandon() {
        close()
        endpoint.launched?.let { process -> LspServerHost.stopTree(process = process) }
    }

    private fun handshake() {
        logger.info { "Kotlin LSP handshake: initialize -> $projectUri (port ${endpoint.server.port})" }
        try {
            val result =
                server
                    .initialize(
                        InitializeParams().apply {
                            capabilities = clientCapabilities()
                            // not our child: a server that watches its parent would otherwise stop with this service
                            processId = null
                            rootUri = projectUri
                        },
                    ).get(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            logger.info { "Kotlin LSP ready pid=${endpoint.server.processId} project=$projectUri" }
            logger.debug { "Kotlin LSP capabilities=${result.capabilities}" }
            server.initialized(InitializedParams())
        } catch (e: InterruptedException) {
            abandon()
            Thread.currentThread().interrupt()
            throw LspInitializationException(message = "Kotlin LSP server start interrupted", cause = e)
        } catch (e: TimeoutException) {
            abandon()
            throw LspInitializationException(
                message = "Kotlin LSP server did not answer its handshake within ${INITIALIZE_TIMEOUT_SECONDS}s: dropped",
                cause = e,
            )
        } catch (e: ExecutionException) {
            abandon()
            throw LspInitializationException(message = "Kotlin LSP server handshake failed: ${e.cause?.message}", cause = e)
        }
    }

    /**
     * Asks for nested document symbols: they carry the exact range of each declaration name.
     * Announces what [WorkspaceEditApplier] can apply (document changes, create/rename/delete)
     * and the file rename notifications, without which a server may send no rename edits at all.
     */
    private fun clientCapabilities(): ClientCapabilities =
        ClientCapabilities().apply {
            textDocument =
                TextDocumentClientCapabilities().apply {
                    documentSymbol = DocumentSymbolCapabilities().apply { hierarchicalDocumentSymbolSupport = true }
                    rename = RenameCapabilities().apply { prepareSupport = true }
                }
            workspace =
                WorkspaceClientCapabilities().apply {
                    workspaceEdit =
                        WorkspaceEditCapabilities().apply {
                            documentChanges = true
                            resourceOperations =
                                listOf(ResourceOperationKind.Create, ResourceOperationKind.Rename, ResourceOperationKind.Delete)
                        }
                    fileOperations =
                        FileOperationsWorkspaceCapabilities().apply {
                            willRename = true
                            didRename = true
                        }
                }
        }

    companion object : KLogging() {
        private const val KOTLIN_LANGUAGE_ID = "kotlin"
        private const val INITIAL_VERSION = 1
        private const val INITIALIZE_TIMEOUT_SECONDS = 30L

        /** Connects to [endpoint]; when it refuses, the server started for it is stopped. */
        private fun connectedSocket(endpoint: ServerEndpoint): Socket =
            try {
                LspServerHost.openSocket(port = endpoint.server.port)
            } catch (e: IOException) {
                endpoint.launched?.let { process -> LspServerHost.stopTree(process = process) }
                throw LspInitializationException(
                    message = "Kotlin LSP server on port ${endpoint.server.port} refused the connection: ${e.message}",
                    cause = e,
                )
            }
    }
}
