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
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.createDirectories
import kotlin.io.path.isExecutable
import kotlin.io.path.readText

/**
 * Launches [serverBinary] with `--stdio` and wires lsp4j to the process stdin/stdout.
 *
 * Performs the LSP handshake (initialize + initialized) right after launch, bounded by [INITIALIZE_TIMEOUT_SECONDS].
 * When it fails, times out or is interrupted, the process is killed and [LspInitializationException] is thrown: the
 * next call starts a new one. The server's stderr is drained on a virtual thread. The process gets a minimal
 * environment, not the one of the service, which holds secrets.
 *
 * @param serverBinary absolute path to the `intellij-server` binary
 * @param projectUri   file URI of the project root
 * @param systemPath   where the server keeps its index, caches and `log/intellij-server.log`, outside the project
 */
internal class LspConnection(
    serverBinary: Path,
    private val projectUri: String,
    systemPath: Path,
) : LanguageServerConnection {
    init {
        require(serverBinary.isExecutable()) { "Kotlin LSP binary not executable: $serverBinary" }
    }

    private val client: LspLanguageClient = LspLanguageClient()

    private val listenerExecutor =
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "kotlin-lsp-listener").also { it.isDaemon = true }
        }

    private val process: Process = startServer(serverBinary = serverBinary, systemPath = systemPath)

    private val launcher: Launcher<LanguageServer> =
        LSPLauncher
            .createClientLauncher(client, process.inputStream, process.outputStream, listenerExecutor) { it }
            .also { it.startListening() }

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

    override fun isAlive(): Boolean = process.isAlive

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
        logger.info { "Closing Kotlin LSP connection pid=${process.pid()} project=$projectUri" }
        runCatching { process.destroyForcibly() }
            .onFailure { logger.warn { "Error destroying Kotlin LSP process: ${it.message}" } }
        listenerExecutor.shutdownNow()
    }

    private fun handshake() {
        logger.info { "Kotlin LSP handshake: initialize -> $projectUri" }
        try {
            val result =
                server
                    .initialize(
                        InitializeParams().apply {
                            capabilities = clientCapabilities()
                            processId = ProcessHandle.current().pid().toInt()
                            rootUri = projectUri
                        },
                    ).get(INITIALIZE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            logger.info { "Kotlin LSP ready pid=${process.pid()} project=$projectUri" }
            logger.debug { "Kotlin LSP capabilities=${result.capabilities}" }
            server.initialized(InitializedParams())
        } catch (e: InterruptedException) {
            close()
            Thread.currentThread().interrupt()
            throw LspInitializationException(message = "Kotlin LSP server start interrupted", cause = e)
        } catch (e: TimeoutException) {
            close()
            throw LspInitializationException(
                message = "Kotlin LSP server did not answer its handshake within ${INITIALIZE_TIMEOUT_SECONDS}s: stopped",
                cause = e,
            )
        } catch (e: ExecutionException) {
            close()
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

        /**
         * Variables of the service the server may need. Everything else is withheld: the service environment
         * holds its encryption key and the keys of the AI providers, and the server is a third-party binary.
         */
        private val INHERITED_ENVIRONMENT =
            listOf(
                "PATH",
                "HOME",
                "USER",
                "LANG",
                "LC_ALL",
                "TMPDIR",
                "JAVA_HOME",
                "GRADLE_USER_HOME",
                "HTTP_PROXY",
                "HTTPS_PROXY",
                "NO_PROXY",
            )

        /** The bundled JARs would try to phone home without this. */
        private val TELEMETRY_DISABLED =
            mapOf(
                "OTEL_SDK_DISABLED" to "true",
                "OTEL_TRACES_EXPORTER" to "none",
                "OTEL_METRICS_EXPORTER" to "none",
                "OTEL_LOGS_EXPORTER" to "none",
            )

        private fun startServer(
            serverBinary: Path,
            systemPath: Path,
        ): Process {
            logger.info { "Starting Kotlin LSP server: $serverBinary, system path: $systemPath" }
            val builder =
                ProcessBuilder(listOf(serverBinary.toString(), "--stdio", "--system-path", systemPath.createDirectories().toString()))
            builder.environment().apply {
                clear()
                INHERITED_ENVIRONMENT.forEach { variable -> System.getenv(variable)?.let { put(variable, it) } }
                putAll(TELEMETRY_DISABLED)
            }
            val process = builder.start()
            Thread.ofVirtual().name("kotlin-lsp-stderr").start { drainStderr(process = process) }
            process.onExit().thenAccept { exited ->
                if (exited.exitValue() == 0) {
                    logger.info { "[intellij-server] pid=${exited.pid()} exited normally" }
                } else {
                    logger.warn { "[intellij-server] pid=${exited.pid()} exited with code ${exited.exitValue()}" }
                }
            }
            return process
        }

        private fun drainStderr(process: Process) {
            runCatching {
                process.errorStream.bufferedReader().useLines { lines -> lines.forEach { logServerStderr(line = it) } }
            }
        }

        private fun logServerStderr(line: String) {
            when {
                line.contains("error", ignoreCase = true) ||
                    line.contains("exception", ignoreCase = true) ||
                    line.contains("warn", ignoreCase = true) -> logger.warn { "[intellij-server] $line" }

                else -> logger.debug { "[intellij-server] $line" }
            }
        }
    }
}
