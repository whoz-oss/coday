package io.whozoss.factory.agentattempt

import io.whozoss.factory.workflow.domain.WorkflowContinuationValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.yaml.snakeyaml.Yaml

/**
 * The 50-step limit of a continuation has ONE source in the code ([WorkflowContinuationValidator.MAX_STEPS]).
 * The OpenAPI contract given to the workers documents the same number: this test keeps the two from drifting apart.
 */
class ContinuationLimitContractTest {

    @Suppress("UNCHECKED_CAST")
    private fun child(node: Any?, vararg path: String): Any? =
        path.fold(node) { current, key -> (current as? Map<String, Any?>)?.get(key) }

    @Test
    fun `the worker contract documents the same maxItems as the validator`() {
        val contract = ClassPathResource("openapi/factory-worker-http-api-submit-step-result.yaml").inputStream.use {
            Yaml().load<Map<String, Any?>>(it)
        }

        val steps = child(
            contract,
            "paths", "/api/factory/agent-step-results", "post", "requestBody", "content", "application/json",
            "schema", "properties", "result", "properties", "continuation", "properties", "steps",
        )

        assertThat(child(steps, "maxItems")).isEqualTo(WorkflowContinuationValidator.MAX_STEPS)
        assertThat(child(steps, "minItems")).isEqualTo(1)
    }
}
