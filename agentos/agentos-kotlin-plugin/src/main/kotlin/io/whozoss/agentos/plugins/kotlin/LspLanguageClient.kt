package io.whozoss.agentos.plugins.kotlin

import mu.KLogging
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.services.LanguageClient
import java.util.concurrent.CompletableFuture

/**
 * Minimal LanguageClient implementation: logs server notifications, no UI interaction.
 *
 * The server also makes requests of its own while it works, and a client has to answer them. lsp4j's answer for a
 * request this class does not implement is an error (`UnsupportedOperationException`, sent as "Internal error"), and the
 * server then fails the request of the plugin that was waiting on it: the trace of a failed `kotlin_lsp_inlay_hints`
 * shows exactly that, a `workspace/configuration` request refused right before the call failed with "Internal error".
 * The ones every client is expected to answer are answered here, with the neutral reply: nothing to configure, nothing
 * to register, nothing to show.
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

    /**
     * The client has no settings of its own: every requested item is answered with null, one entry per item as the
     * protocol requires, which tells the server to use its defaults.
     */
    override fun configuration(configurationParams: ConfigurationParams): CompletableFuture<List<Any?>> {
        logger.debug { "LSP configuration requested: ${configurationParams.items.map { item -> item.section }}" }
        return CompletableFuture.completedFuture<List<Any?>>(configurationParams.items.map { null })
    }

    /** Dynamic registrations are accepted and ignored: the plugin asks for what it needs when it connects. */
    override fun registerCapability(registrationParams: RegistrationParams): CompletableFuture<Void> {
        logger.debug { "LSP registerCapability: ${registrationParams.registrations.map { registration -> registration.method }}" }
        return CompletableFuture.completedFuture<Void>(null)
    }

    override fun unregisterCapability(unregistrationParams: UnregistrationParams): CompletableFuture<Void> {
        logger.debug { "LSP unregisterCapability: ${unregistrationParams.unregisterations.map { entry -> entry.method }}" }
        return CompletableFuture.completedFuture<Void>(null)
    }

    /** Progress is not shown anywhere, but the server may wait for the answer before reporting it. */
    override fun createProgress(progressParams: WorkDoneProgressCreateParams): CompletableFuture<Void> =
        CompletableFuture.completedFuture<Void>(null)

    companion object : KLogging()
}
