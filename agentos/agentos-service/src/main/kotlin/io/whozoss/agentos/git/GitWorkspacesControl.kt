package io.whozoss.agentos.git

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import mu.KLogging
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operator switches for the Git background work, driven by [GitWorkspacesEndpoint].
 *
 * Pausing stops the work between two items: an item already running (a clone, a worktree, a setup)
 * finishes, because interrupting it would leave its state `PREPARING` until the next restart.
 * The switches live in memory, for this instance only, and reset on restart.
 *
 * Registered only with the worker (`agentos.git.worker.enabled`), like its endpoint and gauges.
 */
@Component
@ConditionalOnProperty(prefix = "agentos.git.worker", name = ["enabled"], havingValue = "true")
class GitWorkspacesControl(
    meterRegistry: MeterRegistry = SimpleMeterRegistry(),
) {
    private val provisioningPaused = AtomicBoolean(false)

    init {
        Gauge.builder(PROVISIONING_PAUSED_GAUGE, provisioningPaused) { if (it.get()) 1.0 else 0.0 }
            .description("1 while an operator has paused Git workspace provisioning")
            .register(meterRegistry)
    }

    fun isProvisioningPaused(): Boolean = provisioningPaused.get()

    fun pauseProvisioning() {
        if (provisioningPaused.compareAndSet(false, true)) logger.warn { "Git workspace provisioning paused by an operator" }
    }

    fun resumeProvisioning() {
        if (provisioningPaused.compareAndSet(true, false)) logger.warn { "Git workspace provisioning resumed by an operator" }
    }

    companion object : KLogging() {
        const val PROVISIONING_PAUSED_GAUGE = "agentos.git.worker.paused"
    }
}
