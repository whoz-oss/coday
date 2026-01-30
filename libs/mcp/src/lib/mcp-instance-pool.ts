import { McpToolsFactory } from './mcp-tools-factory'
import { computeMcpConfigHash } from './mcp-instance-key'
import { McpServerConfig } from '@coday/model'

/**
 * Pooled MCP instance with tracking information
 */
interface McpPooledInstance {
  /** The shared MCP tools factory instance */
  mcpFactory: McpToolsFactory

  /** Set of active thread IDs using this instance */
  activeThreads: Set<string>

  /** Configuration hash for this instance */
  configHash: string

  /** Configuration that created this instance (for logging) */
  config: McpServerConfig
}

/**
 * Pool manager for MCP instances.
 *
 * Responsibilities:
 * - Share MCP instances across threads with identical resolved configurations
 * - Track active threads using each MCP instance
 * - Cleanup instances when no threads are using them
 * - Respect noShare flag to prevent sharing of stateful/sensitive MCPs
 *
 * Lifecycle:
 * 1. Thread starts and needs MCP tools
 * 2. Toolbox calls pool.acquire(config, threadId) via factory creator
 * 3. Pool returns existing factory or creates new one
 * 4. Thread terminates
 * 5. Toolbox.kill() calls pool.releaseThread(threadId)
 * 6. Pool removes thread from all instances
 * 7. If instance has no more active threads, kill factory and remove instance
 */
export class McpInstancePool {
  /** Map of config hash to pooled instance */
  private instances: Map<string, McpPooledInstance> = new Map()

  constructor() {}

  /**
   * Get or create an MCP factory for the given configuration and thread.
   *
   * @param config The MCP server configuration
   * @param threadId The thread ID requesting the factory
   * @param factoryCreator Lambda to create a new factory if needed
   * @returns The MCP tools factory (shared or new)
   */
  async getOrCreateFactory(
    config: McpServerConfig,
    threadId: string,
    factoryCreator: () => McpToolsFactory
  ): Promise<McpToolsFactory> {
    const hash = computeMcpConfigHash(config)
    let instance = this.instances.get(hash)

    if (!instance) {
      // Create new instance
      console.log(
        `[MCP_POOL] Creating new MCP instance for ${config.name} (hash: ${hash.substring(0, 8)}) for thread ${threadId}`
      )
      const mcpFactory = factoryCreator()

      instance = {
        mcpFactory,
        activeThreads: new Set(),
        configHash: hash,
        config,
      }
      this.instances.set(hash, instance)
    } else {
      console.log(
        `[MCP_POOL] Reusing existing MCP instance for ${config.name} (${instance.activeThreads.size} active threads) for thread ${threadId}`
      )
    }

    // Register this thread as active
    instance.activeThreads.add(threadId)

    return instance.mcpFactory
  }

  /**
   * Release a thread from all MCP instances it was using.
   * Cleanup instances that have no more active threads.
   *
   * @param threadId The thread ID being released
   */
  async releaseThread(threadId: string): Promise<void> {
    const instancesToCleanup: McpPooledInstance[] = []

    // Remove thread from all instances
    for (const instance of this.instances.values()) {
      if (instance.activeThreads.has(threadId)) {
        instance.activeThreads.delete(threadId)
        console.debug(
          `[MCP_POOL] Released thread ${threadId} from MCP ${instance.config.name} (${instance.activeThreads.size} remaining)`
        )

        // Mark for cleanup if no more active threads
        if (instance.activeThreads.size === 0) {
          instancesToCleanup.push(instance)
        }
      }
    }

    // Cleanup instances with no active threads
    for (const instance of instancesToCleanup) {
      console.log(
        `[MCP_POOL] No more active threads for MCP ${instance.config.name}, killing instance (hash: ${instance.configHash.substring(0, 8)})`
      )

      try {
        await instance.mcpFactory.kill()
        this.instances.delete(instance.configHash)
      } catch (error) {
        console.error(`Error killing MCP instance ${instance.config.name}:`, error)
        // Remove from pool even if kill failed to avoid leaking references
        this.instances.delete(instance.configHash)
      }
    }
  }

  /**
   * Shutdown all MCP instances.
   * Called during server shutdown.
   */
  async shutdown(): Promise<void> {
    console.log(`Shutting down MCP instance pool (${this.instances.size} instances)`)

    const killPromises = Array.from(this.instances.values()).map(async (instance) => {
      try {
        await instance.mcpFactory.kill()
      } catch (error) {
        console.error(`Error killing MCP instance ${instance.config.name} during shutdown:`, error)
      }
    })

    await Promise.all(killPromises)
    this.instances.clear()

    console.log('MCP instance pool shutdown complete')
  }

  /**
   * Get statistics about the pool for monitoring/debugging.
   *
   * @returns Pool statistics
   */
  getStats(): {
    totalInstances: number
    activeInstances: number
    idleInstances: number
    totalActiveThreads: number
    instanceDetails: Array<{
      configName: string
      configHash: string
      activeThreads: number
      lastUsed: number
    }>
  } {
    const instances = Array.from(this.instances.values())

    const activeInstances = instances.filter((i) => i.activeThreads.size > 0)
    const idleInstances = instances.filter((i) => i.activeThreads.size === 0)
    const totalActiveThreads = instances.reduce((sum, i) => sum + i.activeThreads.size, 0)

    const instanceDetails = instances.map((i) => ({
      configName: i.config.name,
      configHash: i.configHash.substring(0, 8),
      activeThreads: i.activeThreads.size,
      lastUsed: i.mcpFactory.lastUsed,
    }))

    return {
      totalInstances: instances.length,
      activeInstances: activeInstances.length,
      idleInstances: idleInstances.length,
      totalActiveThreads,
      instanceDetails,
    }
  }
}
