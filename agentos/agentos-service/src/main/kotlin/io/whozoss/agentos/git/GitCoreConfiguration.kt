package io.whozoss.agentos.git

import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitRemoteUrlValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Service beans for the shared Git core.
 *
 * `agentos-git` holds no Spring components: code loaded outside the application context, such as a
 * PF4J plugin, builds the same runner itself. The service binds the settings from `agentos.git`.
 */
@Configuration
class GitCoreConfiguration {
    @Bean
    fun gitCommandRunner(properties: GitExecutionProperties): GitCommandRunner = GitCommandRunner(properties)

    @Bean
    fun gitRemoteUrlValidator(properties: GitExecutionProperties): GitRemoteUrlValidator = GitRemoteUrlValidator(properties)
}
