package io.whozoss.agentos.caseFlow

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Settings of the side questions (`/btw`, [CaseSideQuestionService]), bound from `agentos.side-question`. */
@ConfigurationProperties(prefix = "agentos.side-question")
data class SideQuestionProperties(
    /**
     * How long after its last use the agent's last request is still reused: the lifetime of the provider's prompt
     * cache. Past it, replaying the whole conversation would write the cache again at full price, which costs more
     * than the short transcript the side question falls back on.
     */
    val snapshotMaxAge: Duration = Duration.ofMinutes(5),
)
