import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.spec.IsolationMode
import kotlin.time.Duration.Companion.seconds

/**
 * Global Kotest configuration for agentos-service tests
 */
class ProjectConfig : AbstractProjectConfig() {
    override val parallelism = 1 // Spring tests should run sequentially
    override val timeout = 30.seconds // Spring context startup can take longer
    override val isolationMode = IsolationMode.InstancePerLeaf
}
