package io.whozoss.agentos.integrationConfig

import io.whozoss.agentos.agent.AgentExecutionContext
import io.whozoss.agentos.agent.RunIntegrationCustomizer
import io.whozoss.agentos.agent.RunIntegrationCustomizerOrder
import io.whozoss.agentos.exchange.ExchangeIntegrationTypes
import io.whozoss.agentos.plugin.filesystem.NAMESPACE_CONFIG_PATH_TOKEN
import io.whozoss.agentos.util.jsonTokensPresent
import io.whozoss.agentos.util.substituteJsonTokens
import mu.KLogging
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Resolves the environment tokens declared in [IntegrationsProperties.environmentTokens] (such as
 * `{{FACTORY_URL}}`) in the parameters of every integration of a run.
 *
 * ## What it is for
 *
 * An integration that calls a service of the deployment (an `HTTP_API` pointing at the Factory, an
 * `MCP_HTTP` at a sibling service) names its address in a parameter, and that address is not the same on
 * a laptop, in the desktop app and in a cluster. Writing it as a token lets the integration be defined
 * once, in the field the operator already edits, while each deployment feeds the value in through its own
 * environment, with the defaults of the Spring configuration.
 *
 * ## A closed list, not a window on the environment
 *
 * Only the tokens an operator declared are resolved. The alternative, letting `{{ANY_PROPERTY}}` read the
 * Spring Environment, would give whoever writes an integration's parameters (namespace admins, and users
 * for some types) a way to read `AGENTOS_ENCRYPTION_KEY` or a provider key into a URL or a command they
 * control. What is declared is a value the operator chose to expose; anything else, including a
 * `{{SOMETHING}}` nobody declared, is plain text and is left exactly as written.
 *
 * ## An unset value removes its integration from the run
 *
 * A declared token whose value is blank, because the environment variable behind it is not set, drops the
 * integration that carries it, with no tool and no exception, exactly as
 * [io.whozoss.agentos.exchange.ExchangePathRunIntegration] does for a token it cannot resolve. Handing the
 * plugin a literal `{{FACTORY_URL}}` would make it call, or run, somewhere nobody looks, and quietly. Only
 * the integration that carries the token is affected: every other one reaches the run untouched, and a
 * config that mentions no declared token is returned as the very same instance.
 *
 * The drop is logged at `warn` on a real run and at `debug` otherwise. This customizer is handed every
 * integration config of the namespace before any filtering by what the agent declares, and it also runs
 * where there is no run at all (a definition preview, an agent listing): one namespace with one such
 * integration would otherwise warn on each of those requests, about an integration nobody asked for.
 *
 * ## Which declarations are honoured
 *
 * Checked once, at construction, and ignored with a warning logged once when they fail:
 * - a key that is not `[A-Z][A-Z0-9_]*`;
 * - a key that spells a token with a meaning of its own, [RESERVED_TOKENS]: the exchange tokens, resolved
 *   per case by [io.whozoss.agentos.exchange.ExchangePathRunIntegration] under permission checks that a
 *   plain value must not be able to shadow, and `{{NAMESPACE_CONFIG_PATH}}`, resolved when a filesystem
 *   config is loaded.
 *
 * A key Spring produced from the environment variable form,
 * `AGENTOS_INTEGRATIONS_ENVIRONMENT_TOKENS_FACTORY_URL`, arrives as `factory.url` (lower case, the
 * underscores read as separators). Such an all-lower-case key is folded back to `FACTORY_URL` rather than
 * ignored, since that variable is the way a deployment adds a token without a configuration file.
 *
 * ## Order against the other customizers
 *
 * It runs last, pinned by [RunIntegrationCustomizerOrder.ENVIRONMENT_TOKENS], but the order is indifferent:
 * the values are controlled by the operator, so they cannot spell an exchange token for the customizer
 * before it to expand, and no exchange path can spell a declared token. The one observable combination,
 * a `GIT` config whose workspace is not usable yet, concerns the exchange tokens alone.
 */
@Component
@Order(RunIntegrationCustomizerOrder.ENVIRONMENT_TOKENS)
class EnvironmentTokenRunIntegration(
    properties: IntegrationsProperties,
) : RunIntegrationCustomizer {
    /** `{{NAME}}` to its value, for the declarations that were honoured. Built once. */
    private val tokens: Map<String, String> = honoured(properties.environmentTokens)
    private val declared: Set<String> = tokens.keys

    override fun customize(
        configs: List<IntegrationConfig>,
        context: AgentExecutionContext,
    ): List<IntegrationConfig> =
        when {
            tokens.isEmpty() -> configs
            else -> configs.mapNotNull { config -> resolved(config, context.caseId != null) }
        }

    /**
     * [config] with its tokens substituted, itself when it carries none, or null when one of the tokens it
     * carries has no value, which removes it from the run.
     */
    private fun resolved(
        config: IntegrationConfig,
        isRun: Boolean,
    ): IntegrationConfig? {
        val used = jsonTokensPresent(config.parameters, declared)
        val unset = used.filter { tokens.getValue(it).isBlank() }
        return when {
            // The common case: nothing to substitute, and the config must not be rebuilt node by node.
            used.isEmpty() -> config

            unset.isEmpty() ->
                config.copy(parameters = substituteJsonTokens(config.parameters, tokens.filterKeys { it in used }))

            else -> {
                logDrop(config, unset, isRun)
                null
            }
        }
    }

    private fun logDrop(
        config: IntegrationConfig,
        unset: List<String>,
        isRun: Boolean,
    ) {
        val message = {
            "Integration '${config.name}' provides no tool: ${unset.joinToString()} has no value, the " +
                "environment variable behind it is not set (see agentos.integrations.environment-tokens)"
        }
        when {
            isRun -> logger.warn(message)
            else -> logger.debug(message)
        }
    }

    private fun honoured(declarations: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        declarations.forEach { (key, value) ->
            val name = canonicalName(key)
            val token = "{{$name}}"
            when {
                !NAME_PATTERN.matches(name) ->
                    logger.warn {
                        "Ignoring environment token '$key': a name is upper case letters, digits and " +
                            "underscores, starting with a letter (agentos.integrations.environment-tokens)"
                    }

                token in RESERVED_TOKENS ->
                    logger.warn {
                        "Ignoring environment token '$key': $token already has a meaning of its own " +
                            "(agentos.integrations.environment-tokens)"
                    }

                else -> result[token] = value
            }
        }
        return result
    }

    /** The name a key stands for: an all-lower-case key is what Spring makes of the environment variable form. */
    private fun canonicalName(key: String): String =
        when {
            ENVIRONMENT_VARIABLE_KEY.matches(key) -> key.replace('.', '_').uppercase()
            else -> key
        }

    companion object : KLogging() {
        private val NAME_PATTERN = Regex("[A-Z][A-Z0-9_]*")
        private val ENVIRONMENT_VARIABLE_KEY = Regex("[a-z][a-z0-9.]*")

        /** Tokens that cannot be declared: another customizer or the filesystem loader owns them. */
        private val RESERVED_TOKENS =
            setOf(
                ExchangeIntegrationTypes.CASE_EXCHANGE_TOKEN,
                ExchangeIntegrationTypes.NAMESPACE_EXCHANGE_TOKEN,
                NAMESPACE_CONFIG_PATH_TOKEN,
            )
    }
}
