package io.whozoss.agentos.config

import com.netflix.discovery.shared.transport.jersey3.Jersey3TransportClientFactories
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Provides the [Jersey3TransportClientFactories] bean required by
 * [org.springframework.cloud.netflix.eureka.EurekaClientAutoConfiguration].
 *
 * Spring Cloud 2024.0.x (eureka-client 2.0.x) uses Jersey3 as its sole HTTP
 * transport for Eureka communication. The bean is only active when the 'whoz'
 * profile is enabled and the Jersey3 class is on the classpath.
 */
@Configuration
@Profile("whoz")
@ConditionalOnClass(Jersey3TransportClientFactories::class)
class Jersey3TransportClientFactoriesConfig {

    @Bean
    fun jersey3TransportClientFactories(): Jersey3TransportClientFactories =
        Jersey3TransportClientFactories.getInstance()
}
