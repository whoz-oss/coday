package io.whozoss.factory.web

import io.swagger.v3.oas.annotations.Hidden
import io.whozoss.factory.proxy.ProxyProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Bootstrap configuration consumed by the vanilla cockpit
 * (`GET /api/config`).
 *
 * The Node dashboard exposed the same route so the client could build deep
 * links without hard-coding a base URL. `agentosUrl` is bound from
 * `factory.proxy.agentos-url` (`AGENTOS_URL`); `codayExpressUrl` is not
 * configured in this service yet and stays `null`, which the cockpit renders as
 * unclickable text (never a link built from thread input).
 *
 * Hidden from the generated OpenAPI document: it is an internal, same-origin
 * cockpit bootstrap route.
 */
@Hidden
@RestController
@RequestMapping("/api")
class ConfigController(private val proxyProperties: ProxyProperties) {

    @GetMapping("/config")
    fun config(): FactoryDataEnvelope<Map<String, Any?>> =
        FactoryDataEnvelope(
            mapOf(
                "agentosUrl" to proxyProperties.agentosUrl,
                "codayExpressUrl" to null,
            ),
        )
}
