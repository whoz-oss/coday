package io.whozoss.agentos.plugins.http.openapi

import io.whozoss.agentos.plugins.http.config.AuthConfig
import io.whozoss.agentos.plugins.http.config.HttpApiConfig

/**
 * Selects the operations of an [OpenApiDocument] that become tools, in this order:
 *
 * 1. drop deprecated operations;
 * 2. drop operations with an unsupported request body (reported);
 * 3. drop operations with a required header parameter that is not a valid HTTP header name, is
 *    [ReservedHeaders]-reserved, is set by the config `defaultHeaders` or shares its name with a
 *    path/query parameter, or with a required cookie parameter (reported);
 * 4. drop operations with both a request body and an exposed parameter named `body` (reported);
 * 5. drop non-GET operations unless `allowMutations`;
 * 6. apply `includeTags`, `includePathPrefixes`, `includeOperations` and `excludeOperations`;
 * 7. sort by `(path, method name)`, both alphabetically;
 * 8. fail with [CurationResult.TooManyOperations] when more than `maxTools` remain;
 * 9. drop, from the remaining operations, the optional header parameters that are not valid HTTP header
 *    names, are reserved, are set by `defaultHeaders` or share their name with a path/query parameter
 *    (reported, the operation is kept).
 *
 * Operations skipped by the reader are carried into the warnings as well.
 */
object OperationCurator {

    /** @param auth The effective API key placement, whose header is reserved too. */
    fun curate(
        document: OpenApiDocument,
        config: HttpApiConfig,
        configName: String,
        auth: AuthConfig = config.auth,
    ): CurationResult {
        val skippedWarnings = document.skipped.map { CurationWarning(operationKey = it.key, reason = it.reason) }
        val pinned = PinnedHeaders(auth = auth, defaultHeaderNames = config.defaultHeaders.keys)
        val classified = document.operations.filterNot { it.deprecated }.map { it to rejectionReason(it, pinned) }
        val rejectionWarnings = classified.mapNotNull { (operation, reason) ->
            reason?.let { CurationWarning(operationKey = operation.key, reason = it) }
        }
        val includeGlobs = config.includeOperations.map(::globToRegex)
        val excludeGlobs = config.excludeOperations.map(::globToRegex)
        val selected = classified
            .filter { (_, reason) -> reason == null }
            .map { (operation, _) -> operation }
            .filter { it.method == HttpMethod.GET || config.allowMutations }
            .filter { matchesFilters(it, config, includeGlobs, excludeGlobs) }
            .sortedWith(compareBy({ it.path }, { it.method.name }))
        if (selected.size > config.maxTools) {
            return CurationResult.TooManyOperations(
                count = selected.size,
                max = config.maxTools,
                warnings = skippedWarnings + rejectionWarnings,
            )
        }
        val prepared = selected.map { withoutIgnoredHeaders(it, pinned) }
        val candidates = prepared.map { it.operation }
        val headerWarnings = prepared.flatMap { it.warnings }
        val suffixes = ToolNaming.assignUniqueSuffixes(
            configName = configName,
            rawSuffixes = candidates.map { ToolNaming.suffixFor(it.operationId, it.method, it.path) },
        )
        val built = candidates.zip(suffixes).map { (operation, suffix) ->
            operation to OperationDescriptorBuilder.build(operation, toolSuffix = suffix, config = config)
        }
        val reductionWarnings = built
            .filter { (_, result) -> result.schemaReduced }
            .map { (operation, _) ->
                CurationWarning(
                    operationKey = operation.key,
                    reason = "input schema reduced: nested object schemas were collapsed",
                )
            }
        return CurationResult.Selected(
            operations = built.map { (_, result) -> result.descriptor },
            warnings = skippedWarnings + rejectionWarnings + headerWarnings + reductionWarnings,
        )
    }

    /** The headers the agent must not set: the reserved ones and the ones the administrator pins in the config. */
    private class PinnedHeaders(val auth: AuthConfig, val defaultHeaderNames: Set<String>) {
        fun isDefaultHeader(name: String): Boolean = defaultHeaderNames.any { it.equals(name, ignoreCase = true) }
    }

    /** Why the operation cannot be exposed as a tool, or null when it can. */
    private fun rejectionReason(operation: OpenApiOperation, pinned: PinnedHeaders): String? {
        val unusableHeader = operation.parameters
            .filter { it.required && it.location == ParameterLocation.HEADER }
            .firstNotNullOfOrNull { header -> headerIssue(header, operation, pinned)?.let { header.name to it } }
        return when {
            operation.unsupportedBody ->
                "request body has no supported media type (application/json or form-urlencoded)"
            unusableHeader != null ->
                "required header parameter '${unusableHeader.first}' ${unusableHeader.second}"
            operation.hasRequired(ParameterLocation.COOKIE) -> "required cookie parameters are not supported"
            operation.hasBodyPropertyCollision() ->
                "parameter named '${JsonSchemaBuilder.BODY_PROPERTY}' collides with the request body property"
            else -> null
        }
    }

    /** Why a header parameter cannot become a tool argument, or null when it can. */
    private fun headerIssue(header: OpenApiParameter, operation: OpenApiOperation, pinned: PinnedHeaders): String? =
        when {
            !HEADER_NAME.matches(header.name) -> "is not a valid HTTP header name"
            ReservedHeaders.isReserved(header.name, pinned.auth) -> "is reserved and cannot be set by the agent"
            pinned.isDefaultHeader(header.name) -> "is set by 'defaultHeaders' and cannot be changed by the agent"
            operation.parameters.any { it.location in URL_LOCATIONS && it.name == header.name } ->
                "is already used by a path or query parameter"
            else -> null
        }

    private class Prepared(val operation: OpenApiOperation, val warnings: List<CurationWarning>)

    /** The operation without its optional header parameters that cannot be exposed, each one reported. */
    private fun withoutIgnoredHeaders(operation: OpenApiOperation, pinned: PinnedHeaders): Prepared {
        val issues = operation.parameters
            .filter { it.location == ParameterLocation.HEADER }
            .mapNotNull { header -> headerIssue(header, operation, pinned)?.let { header to it } }
        val ignored = issues.map { (header, _) -> header }.toSet()
        return Prepared(
            operation = operation.copy(parameters = operation.parameters.filterNot { it in ignored }),
            warnings = issues.map { (header, issue) ->
                CurationWarning(
                    operationKey = operation.key,
                    reason = "header parameter '${header.name}' ignored: $issue",
                )
            },
        )
    }

    private fun OpenApiOperation.hasRequired(location: ParameterLocation): Boolean =
        parameters.any { it.required && it.location == location }

    private fun OpenApiOperation.hasBodyPropertyCollision(): Boolean =
        requestBody != null &&
            parameters.any {
                it.name == JsonSchemaBuilder.BODY_PROPERTY && it.location in JsonSchemaBuilder.EXPOSED_LOCATIONS
            }

    private fun matchesFilters(
        operation: OpenApiOperation,
        config: HttpApiConfig,
        includeGlobs: List<Regex>,
        excludeGlobs: List<Regex>,
    ): Boolean =
        (config.includeTags.isEmpty() || operation.tags.any { it in config.includeTags }) &&
            (config.includePathPrefixes.isEmpty() || hasIncludedPrefix(operation, config)) &&
            (includeGlobs.isEmpty() || matchesAnyGlob(operation.operationId, includeGlobs)) &&
            !matchesAnyGlob(operation.operationId, excludeGlobs)

    private fun hasIncludedPrefix(operation: OpenApiOperation, config: HttpApiConfig): Boolean =
        config.includePathPrefixes.any { operation.path.startsWith(it) }

    private fun matchesAnyGlob(operationId: String?, globs: List<Regex>): Boolean =
        operationId != null && globs.any { it.matches(operationId) }

    private fun globToRegex(glob: String): Regex =
        Regex(glob.split('*').joinToString(".*", transform = ::wildcardSegment))

    /** A text segment between two `*`, with each `?` matching one character. */
    private fun wildcardSegment(segment: String): String =
        segment.split('?').joinToString(".", transform = Regex::escape)

    /** Parameter locations that name a URL part, with which a header parameter must not share its name. */
    private val URL_LOCATIONS = setOf(ParameterLocation.PATH, ParameterLocation.QUERY)

    /** An HTTP field name is a token (RFC 9110 section 5.1); the HTTP client refuses anything else at call time. */
    private val HEADER_NAME = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")
}
