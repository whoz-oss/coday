package io.whozoss.agentos.persistence.neo4j

import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.spec.Spec
import org.testcontainers.DockerClientFactory
import kotlin.reflect.KClass

/**
 * Kotest [EnabledCondition] that enables a spec only when a working Docker
 * environment is available.
 *
 * The Testcontainers-based Neo4j specs (`@ActiveProfiles("test", "neo4j")`) need a
 * container runtime to start their [org.testcontainers.containers.Neo4jContainer].
 * On machines without Docker (e.g. local dev, sandboxes, some CI runners) those
 * specs cannot start a Spring context at all and fail with
 * `DockerClientProviderStrategy: ... Docker environment failed`.
 *
 * Annotate a spec with `@EnabledIf(DockerAvailableCondition::class)` to have it
 * silently skipped when Docker is unavailable, while still running normally wherever
 * Docker is present. This keeps `./gradlew build` green in both environments without
 * weakening coverage where containers can actually run.
 */
class DockerAvailableCondition : EnabledCondition {
    override fun enabled(kclass: KClass<out Spec>): Boolean =
        runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
}
