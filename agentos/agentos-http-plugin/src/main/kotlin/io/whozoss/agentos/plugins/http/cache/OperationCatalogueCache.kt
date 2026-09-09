package io.whozoss.agentos.plugins.http.cache

import io.whozoss.agentos.plugins.http.auth.ApiKeyPlacementResolver
import io.whozoss.agentos.plugins.http.auth.ResolvedAuth
import io.whozoss.agentos.plugins.http.config.ApiKeyPlacement
import io.whozoss.agentos.plugins.http.config.HttpApiConfig
import io.whozoss.agentos.plugins.http.file.FileReadOutcome
import io.whozoss.agentos.plugins.http.file.SpecFileReader
import io.whozoss.agentos.plugins.http.file.SpecFileSource
import io.whozoss.agentos.plugins.http.net.FetchOutcome
import io.whozoss.agentos.plugins.http.net.OutboundUrlPolicy
import io.whozoss.agentos.plugins.http.net.SpecSource
import io.whozoss.agentos.plugins.http.openapi.CurationResult
import io.whozoss.agentos.plugins.http.openapi.OpenApiReader
import io.whozoss.agentos.plugins.http.openapi.OperationCurator
import mu.KLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Plugin-wide cache of curated [Catalogue]s keyed by [HttpApiConfigHash], so that the OpenAPI document is
 * fetched, parsed and curated once per distinct config rather than on every agent run.
 *
 * - An inline document never expires: its content is part of the key.
 * - A URL document is stale once `spec.refreshMinutes` have elapsed since it was loaded (0 = never); a
 *   stale entry is re-fetched conditionally (`If-None-Match`), a 304 only restarts the interval.
 * - A file document never expires either: the file's stamp (last modification time and size, taken on every
 *   call) is part of the key, so a changed file is a miss that reads it again under a new key, and a
 *   missing or unreadable file is a miss that fails; the previous entry is left to the eviction. Whatever
 *   fails on a file document (missing, oversize, unreadable, unparsable...) is reported with the fixed
 *   [FILE_LOAD_FAILED] reason, the detail going to the ERROR log only: the file lives on the service host
 *   and its existence, size or parse errors are not for the agent prompt.
 * - A load fails when a `defaultHeaders` name equals the effective API key header (config or document
 *   scheme), which is why the default header names are part of the key.
 * - A URL refresh that fails keeps serving the previous catalogue (reported as
 *   [CatalogueOutcome.Ready.staleReason]) and restarts the interval so the source is not hammered; a failure
 *   with nothing cached is [CatalogueOutcome.Failed] and is retried on the next call, never cached as a success.
 * - Loads are serialised by a 64-stripe lock so concurrent runs of the same config do not stampede the
 *   source; the lock only covers the load, which includes the fetch (up to 15 s), so two distinct configs
 *   hashing to the same stripe can wait for each other. Acceptable for a handful of configs per service.
 * - At most [maxEntries] entries are kept, least recently used first out.
 *
 * Credentials are never part of a key nor of an entry.
 *
 * @param urlPolicy Applied to the document server URL when the config declares no `baseUrl`.
 */
class OperationCatalogueCache(
    private val fetcher: SpecSource,
    private val fileSource: SpecFileSource = SpecFileReader(),
    private val urlPolicy: OutboundUrlPolicy = OutboundUrlPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    /** @property etag Of a URL document, null otherwise. */
    private class Entry(val catalogue: Catalogue, val loadedAt: Instant, val etag: String? = null)

    private sealed interface Loaded {
        class Success(val entry: Entry) : Loaded
        data object Unchanged : Loaded
        class Failure(val reason: String) : Loaded
    }

    private val entries = object : LinkedHashMap<String, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean = size > maxEntries
    }
    private val stripes = Array(STRIPES) { Any() }

    /** The cached catalogue for [config] when present, whatever its freshness; never loads. */
    fun peek(config: HttpApiConfig): Catalogue? = get(key(config))?.catalogue

    fun getOrLoad(configName: String, config: HttpApiConfig): CatalogueOutcome {
        val key = key(config)
        synchronized(stripes[Math.floorMod(key.hashCode(), STRIPES)]) {
            val existing = get(key)
            if (existing != null && isFresh(existing, config)) return CatalogueOutcome.Ready(existing.catalogue)
            return load(key = key, configName = configName, config = config, existing = existing)
        }
    }

    /** The stamp of a file document is read here, on every call, so that a changed file is a different key. */
    private fun key(config: HttpApiConfig): String =
        HttpApiConfigHash.of(config, fileStamp = config.spec.file?.let(fileSource::stamp))

    private fun isFresh(entry: Entry, config: HttpApiConfig): Boolean {
        val spec = config.spec
        return when {
            spec.inline != null || spec.file != null -> true
            spec.refreshMinutes == 0 -> true
            else -> {
                val age = Duration.between(entry.loadedAt, clock.instant())
                age <= Duration.ofMinutes(spec.refreshMinutes.toLong())
            }
        }
    }

    private fun load(key: String, configName: String, config: HttpApiConfig, existing: Entry?): CatalogueOutcome {
        val spec = config.spec
        val loaded = when {
            spec.inline != null -> build(spec.inline, etag = null, configName = configName, config = config)
            spec.file != null -> readAndBuild(spec.file, configName = configName, config = config)
            else -> fetchAndBuild(configName, config, existing)
        }
        return when (loaded) {
            is Loaded.Success -> {
                put(key, loaded.entry)
                CatalogueOutcome.Ready(loaded.entry.catalogue)
            }
            Loaded.Unchanged -> {
                val entry = checkNotNull(existing) { "304 is only possible with a cached document" }
                put(key, Entry(entry.catalogue, loadedAt = clock.instant(), etag = entry.etag))
                CatalogueOutcome.Ready(entry.catalogue)
            }
            is Loaded.Failure -> {
                if (existing == null) return CatalogueOutcome.Failed(loaded.reason)
                logger.warn {
                    "HTTP_API '$configName': document refresh failed (${loaded.reason}), serving the cached catalogue"
                }
                put(key, Entry(existing.catalogue, clock.instant(), etag = existing.etag))
                CatalogueOutcome.Ready(existing.catalogue, staleReason = loaded.reason)
            }
        }
    }

    private fun fetchAndBuild(configName: String, config: HttpApiConfig, existing: Entry?): Loaded {
        val url = checkNotNull(config.spec.url) { "config has no spec source" }
        return when (val outcome = fetcher.fetch(url, maxBytes = config.spec.maxBytes, etag = existing?.etag)) {
            is FetchOutcome.Fetched ->
                build(outcome.text, etag = outcome.etag, configName = configName, config = config)
            FetchOutcome.NotModified ->
                if (existing != null) Loaded.Unchanged else Loaded.Failure(UNEXPECTED_NOT_MODIFIED)
            is FetchOutcome.Failed -> Loaded.Failure(outcome.reason)
        }
    }

    private fun readAndBuild(file: String, configName: String, config: HttpApiConfig): Loaded {
        val loaded = when (val outcome = fileSource.read(file, maxBytes = config.spec.maxBytes)) {
            is FileReadOutcome.Read -> build(outcome.text, etag = null, configName = configName, config = config)
            is FileReadOutcome.Failed -> Loaded.Failure(outcome.reason)
        }
        if (loaded !is Loaded.Failure) return loaded
        logger.error { "HTTP_API '$configName': document file cannot be loaded: ${loaded.reason}" }
        return Loaded.Failure(FILE_LOAD_FAILED)
    }

    private fun build(text: String, etag: String?, configName: String, config: HttpApiConfig): Loaded {
        val document = try {
            OpenApiReader.read(text, maxBytes = config.spec.maxBytes)
        } catch (e: IllegalArgumentException) {
            return Loaded.Failure(e.message ?: "unreadable OpenAPI document")
        }
        val resolution =
            BaseUrlResolver.resolve(configured = config.baseUrl, serverUrl = document.serverUrl, urlPolicy = urlPolicy)
        val baseUrl = when (resolution) {
            is BaseUrlResolution.Resolved -> resolution.url
            is BaseUrlResolution.Unusable -> return Loaded.Failure(resolution.reason)
        }
        val auth = ApiKeyPlacementResolver.resolve(config.auth, document.apiKeyScheme, configName)
        apiKeyHeaderCollision(config, auth)?.let { return Loaded.Failure(it) }
        return when (val curated = OperationCurator.curate(document, config, configName, auth = auth.auth)) {
            is CurationResult.Selected -> {
                logWarnings(configName, curated)
                val catalogue = Catalogue(
                    title = document.title,
                    version = document.version,
                    baseUrl = baseUrl,
                    auth = auth.auth,
                    authFromDocument = auth.fromDocument,
                    operations = curated.operations,
                    warnings = curated.warnings,
                    readOnly = !config.allowMutations,
                )
                Loaded.Success(Entry(catalogue, clock.instant(), etag = etag))
            }
            is CurationResult.TooManyOperations ->
                Loaded.Failure("${curated.count} operations selected, exceeds maxTools=${curated.max}")
        }
    }

    /**
     * Why a `defaultHeaders` entry cannot coexist with the effective API key placement, or null: a default
     * header named like the API key header would be silently overwritten by the credential on every call.
     */
    private fun apiKeyHeaderCollision(config: HttpApiConfig, auth: ResolvedAuth): String? {
        if (auth.auth.apiKeyIn != ApiKeyPlacement.HEADER) return null
        val name = config.defaultHeaders.keys.firstOrNull { it.equals(auth.auth.apiKeyName, ignoreCase = true) }
            ?: return null
        val origin = if (auth.fromDocument) "the document security scheme" else "the config"
        return "'defaultHeaders' must not set '$name': it is the API key header '${auth.auth.apiKeyName}' of $origin"
    }

    private fun logWarnings(configName: String, curated: CurationResult.Selected) {
        if (curated.warnings.isEmpty()) return
        val shown = curated.warnings.take(WARNINGS_SHOWN).joinToString("; ") { "${it.operationKey}: ${it.reason}" }
        logger.warn {
            "HTTP_API '$configName': ${curated.operations.size} operation(s) exposed, " +
                "${curated.warnings.size} warning(s): $shown"
        }
    }

    private fun get(key: String): Entry? = synchronized(entries) { entries[key] }

    private fun put(key: String, entry: Entry) {
        synchronized(entries) { entries[key] = entry }
    }

    companion object : KLogging() {
        const val DEFAULT_MAX_ENTRIES = 200

        /** The only reason ever reported for a `spec.file` failure; the detail is in the ERROR log. */
        const val FILE_LOAD_FAILED = "document file cannot be loaded, see the service logs"
        private const val STRIPES = 64
        private const val INITIAL_CAPACITY = 16
        private const val LOAD_FACTOR = 0.75f
        private const val WARNINGS_SHOWN = 5
        private const val UNEXPECTED_NOT_MODIFIED = "server answered 304 to an unconditional request"
    }
}
