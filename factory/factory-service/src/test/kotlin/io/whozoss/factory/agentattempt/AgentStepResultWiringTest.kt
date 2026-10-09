package io.whozoss.factory.agentattempt

import io.whozoss.factory.Neo4jIntegrationTest
import io.whozoss.factory.agentattempt.persistence.AgentStepResultRepository
import io.whozoss.factory.agentattempt.persistence.IdempotencyRepository
import io.whozoss.factory.agentattempt.service.AgentStepResultService
import io.whozoss.factory.agentattempt.web.AgentStepResultController
import io.whozoss.factory.agentattempt.web.FactoryStepResultBindingController
import io.whozoss.factory.capability.CapabilityExecutionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.test.util.AopTestUtils

/**
 * Composition-root lock of the result channel (worker → Factory).
 *
 * There must be exactly ONE shared instance of [AgentStepResultService],
 * [AgentStepResultRepository] and [IdempotencyRepository] between the runner
 * ([CapabilityExecutionService]) and the HTTP controllers
 * ([AgentStepResultController], [FactoryStepResultBindingController]): no
 * secondary dependency may re-instantiate the store or the business logic of
 * the result/capability aggregate. All four types are Spring stereotypes
 * scanned once under `io.whozoss.factory`; this test pins that invariant so a
 * future refactor cannot silently fork the channel.
 */
class AgentStepResultWiringTest : Neo4jIntegrationTest() {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `exactly one bean of each result-channel type is registered`() {
        assertThat(context.getBeanNamesForType(AgentStepResultService::class.java)).hasSize(1)
        assertThat(context.getBeanNamesForType(AgentStepResultRepository::class.java)).hasSize(1)
        assertThat(context.getBeanNamesForType(IdempotencyRepository::class.java)).hasSize(1)
    }

    @Test
    fun `the runner and both http controllers share the single result service`() {
        val service = context.getBean(AgentStepResultService::class.java)

        val submissionController = context.getBean(AgentStepResultController::class.java)
        val bindingController = context.getBean(FactoryStepResultBindingController::class.java)
        val runner = context.getBean(CapabilityExecutionService::class.java)

        assertThat(injected(submissionController, "service")).isSameAs(service)
        assertThat(injected(bindingController, "service")).isSameAs(service)
        assertThat(injected(runner, "agentStepResultService")).isSameAs(service)
    }

    @Test
    fun `the result service is backed by the single repository instances`() {
        val service = context.getBean(AgentStepResultService::class.java)
        // The bean is a CGLIB proxy (transactional): fields live on the target.
        val target = AopTestUtils.getTargetObject<Any>(service)

        assertThat(injected(target, "results")).isSameAs(context.getBean(AgentStepResultRepository::class.java))
        assertThat(injected(target, "idempotency")).isSameAs(context.getBean(IdempotencyRepository::class.java))
    }

    /** Reads a private field, walking superclasses in case of a proxy. */
    private fun injected(target: Any, fieldName: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                field.trySetAccessible()
                return field.get(target)
            }
            type = type.superclass
        }
        error("Field '$fieldName' not found on ${target.javaClass.name}")
    }
}
