package io.whozoss.factory.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Enables Spring scheduling for the transactional-outbox drain worker.
 *
 * The whole configuration is gated on `factory.outbox.drain-enabled` so the
 * background drain can be turned off (as the shared integration-test context
 * does) without disabling Spring's scheduling infrastructure elsewhere.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "factory.outbox", name = ["drain-enabled"], havingValue = "true")
class OutboxSchedulingConfiguration
