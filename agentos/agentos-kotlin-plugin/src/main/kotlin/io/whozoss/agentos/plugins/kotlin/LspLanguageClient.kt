package io.whozoss.agentos.plugins.kotlin

import mu.KLogging
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture

/**
 * Minimal LanguageClient implementation: logs server notifications, no UI interaction.
 */
internal class LspLanguageClient : LanguageClient {
    override fun telemetryEvent(obj: Any?) {
        logger.debug { "LSP telemetry: $obj" }
    }

    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {
        logger.debug { "LSP diagnostics for ${diagnostics.uri}: ${diagnostics.diagnostics.size} items" }
    }

    override fun showMessage(messageParams: MessageParams) {
        logger.info { "LSP [${messageParams.type}] ${messageParams.message}" }
    }

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> {
        logger.info { "LSP showMessageRequest: ${requestParams.message}" }
        return CompletableFuture.completedFuture(null)
    }

    override fun logMessage(message: MessageParams) {
        logger.debug { "LSP log [${message.type}] ${message.message}" }
    }

    companion object : KLogging()
}
