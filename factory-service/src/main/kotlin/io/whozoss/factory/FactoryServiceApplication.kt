package io.whozoss.factory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * Entry point of the autonomous Factory Service.
 *
 * This is the *socle*: no domain aggregate, no business controller. It wires the
 * HTTP boundary (correlation id, trust context, admin guard), the error
 * envelope matching the Node contract, OpenAPI, and the PostgreSQL/Flyway
 * persistence foundation.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class FactoryServiceApplication

fun main(args: Array<String>) {
    runApplication<FactoryServiceApplication>(*args)
}
