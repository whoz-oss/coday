package io.whozoss.agentos.plugin.filesystem

import mu.KLogging
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic TTL cache for entities loaded from YAML files in a directory.
 *
 * Each cache instance is bound to a single [directory]. Entries are (re)loaded
 * when the cache is empty or when [ttl] has elapsed since the last load.
 *
 * [parser] receives each regular `.yaml` / `.yml` file and returns the parsed
 * entity, or null if the file should be skipped (parse error, invalid content).
 * Errors inside [parser] are caught and logged — a bad file never prevents the
 * rest of the directory from loading.
 *
 * Thread-safe: concurrent calls to [getAll] during a reload are serialised via
 * the instance lock. The lock is only held during the filesystem scan, not during
 * the return of the cached list.
 */
class FilesystemYamlCache<T>(
    private val directory: Path,
    private val parser: (Path) -> T?,
    private val ttl: Duration = DEFAULT_TTL,
) {
    private data class CacheEntry<T>(val items: List<T>, val loadedAt: Instant)

    @Volatile
    private var entry: CacheEntry<T>? = null

    fun getAll(): List<T> {
        val now = Instant.now()
        val current = entry
        if (current != null && Duration.between(current.loadedAt, now) < ttl) {
            return current.items
        }
        return reload(now)
    }

    @Synchronized
    private fun reload(now: Instant): List<T> {
        // double-checked: another thread may have reloaded while we waited for the lock
        val current = entry
        if (current != null && Duration.between(current.loadedAt, now) < ttl) {
            return current.items
        }

        if (!Files.exists(directory)) {
            logger.warn { "[FilesystemYamlCache] Directory does not exist: $directory" }
            entry = CacheEntry(emptyList(), now)
            return emptyList()
        }
        if (!Files.isDirectory(directory)) {
            logger.error { "[FilesystemYamlCache] Path is not a directory: $directory" }
            entry = CacheEntry(emptyList(), now)
            return emptyList()
        }

        val items =
            Files
                .walk(directory)
                .use { stream ->
                    stream
                        .filter { Files.isRegularFile(it) }
                        .filter { it.toString().endsWith(".yaml") || it.toString().endsWith(".yml") }
                        .toList()
                }
                .mapNotNull { file: Path ->
                    runCatching { parser(file) }
                        .onFailure { logger.error(it) { "[FilesystemYamlCache] Failed to parse $file: ${it.message}" } }
                        .getOrNull()
                }

        logger.debug { "[FilesystemYamlCache] Loaded ${items.size} item(s) from $directory" }
        entry = CacheEntry(items, now)
        return items
    }

    companion object : KLogging() {
        val DEFAULT_TTL: Duration = Duration.ofMinutes(5)
    }
}

/**
 * Manages one [FilesystemYamlCache] per namespace directory, keyed by the resolved
 * directory [Path]. Caches are created lazily on first access and reused across calls.
 *
 * Use this when a single Spring bean needs to serve multiple namespaces, each with
 * its own configPath:
 * ```kotlin
 * private val caches = FilesystemYamlCacheRegistry(::parseAgentYaml, Duration.ofMinutes(5))
 * val agents = caches.getAll(Path.of(namespace.configPath!!, "agents"))
 * ```
 */
class FilesystemYamlCacheRegistry<T>(
    private val parser: (Path) -> T?,
    private val ttl: Duration = FilesystemYamlCache.DEFAULT_TTL,
) {
    private val caches = ConcurrentHashMap<Path, FilesystemYamlCache<T>>()

    fun getAll(directory: Path): List<T> =
        caches.computeIfAbsent(directory) { FilesystemYamlCache(it, parser, ttl) }.getAll()
}
