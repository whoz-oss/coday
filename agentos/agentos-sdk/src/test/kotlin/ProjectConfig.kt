import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.IsolationMode
import kotlin.time.Duration.Companion.seconds

/**
 * Global Kotest configuration for agentos-sdk tests
 */
class ProjectConfig : AbstractProjectConfig() {
    override val parallelism = 3
    override val timeout = 10.seconds
    override val isolationMode = IsolationMode.InstancePerLeaf
}
