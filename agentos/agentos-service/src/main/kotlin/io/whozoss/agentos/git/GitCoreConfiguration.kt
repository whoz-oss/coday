package io.whozoss.agentos.git

import io.whozoss.agentos.git.core.GitCommandRunner
import io.whozoss.agentos.git.core.GitExecutionProperties
import io.whozoss.agentos.git.core.GitRemoteUrlValidator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Service beans for the shared Git core.
 *
 * `agentos-git` holds no Spring components. The service binds the settings from `agentos.git`, and
 * hands them to the GIT plugin's tool provider, which builds its own runner from them.
 */
@Configuration
class GitCoreConfiguration {
    @Bean
    fun gitCommandRunner(properties: GitExecutionProperties): GitCommandRunner = GitCommandRunner(properties)

    @Bean
    fun gitRemoteUrlValidator(properties: GitExecutionProperties): GitRemoteUrlValidator = GitRemoteUrlValidator(properties)
}
