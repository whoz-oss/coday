package io.whozoss.agentos.plugins.factorybridge

import io.whozoss.agentos.sdk.spi.ExternalExecutionContextProvider
import org.pf4j.Extension
import java.util.UUID

/**
 * Contributes the active Factory step-result capability to the message session context.
 *
 * When the host runtime has bound a Factory result capability to the case (see
 * [FactoryStepResultBindingRegistry]), this provider exposes the opaque `capabilityToken`,
 * `attemptId` and `runtimeId` so downstream tool calls can submit the
 * authoritative structured result without the model ever handling the token.
 *
 * Returns an empty map when no binding is active, so behaviour is unchanged for ordinary
 * cases.
 */
@Extension
class FactoryExternalExecutionContextProvider
    @JvmOverloads
    constructor(
        private val services: () -> FactoryBridgeServices = { FactoryBridgePluginHolder.current },
    ) : ExternalExecutionContextProvider {
        override fun provideExecutionContext(
            caseId: UUID,
            namespaceId: UUID,
            userId: UUID?,
        ): Map<String, Any?> = services().stepResultBindings.contextForCase(caseId, namespaceId)
    }
