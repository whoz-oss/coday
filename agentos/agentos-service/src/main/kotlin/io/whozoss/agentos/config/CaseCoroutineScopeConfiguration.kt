package io.whozoss.agentos.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides the shared [CoroutineScope] used by the case execution engine.
 *
 * A single scope is shared between [io.whozoss.agentos.caseFlow.CaseServiceImpl] and
 * [io.whozoss.agentos.caseFlow.postprocessing.CasePostProcessingService] so that
 * post-processing coroutines are cancelled cleanly when the service shuts down.
 *
 * [SupervisorJob] ensures that a failure in one child coroutine (e.g. a post-processor
 * error) does not cancel sibling coroutines (e.g. running agent turns).
 */
@Configuration
class CaseCoroutineScopeConfiguration {
    @Bean(name = ["caseCoroutineScope"])
    fun caseCoroutineScope(): CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
}
