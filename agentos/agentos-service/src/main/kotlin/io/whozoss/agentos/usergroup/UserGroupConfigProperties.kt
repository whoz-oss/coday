package io.whozoss.agentos.usergroup

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "agentos.user-group")
data class UserGroupConfigProperties(
    val maxUsersPerGroup: Int = 30_000,
    val maxAgentsPerGroup: Int = 1_000,
)
