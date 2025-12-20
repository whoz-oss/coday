import { CodayConfig } from './coday-config'

/**
 * Merge multiple CodayConfig objects according to the stacking order.
 * Later configs override earlier configs for simple properties.
 * Arrays (ai, mcp.servers, etc.) are merged intelligently.
 *
 * Merging rules:
 * - **context**: Concatenated with separator
 * - **ai**: Providers merged by name, models merged by name within provider
 * - **mcp**: Servers merged by id
 * - **integrations**: Merged by key
 * - **defaultAgent**: Last value wins
 *
 * Note: The `projects` property (from UserConfig) is never merged.
 * It only exists at the user global level and is used to extract per-project configs.
 *
 * @param configs Array of configs to merge, in order of increasing priority
 * @returns Merged configuration
 *
 * @example
 * ```typescript
 * const userConfig = { version: 1, context: "I am a developer", ai: [{ name: "openai", apiKey: "sk-xxx" }] }
 * const projectConfig = { version: 1, context: "This is a TypeScript project", ai: [{ name: "openai", model: "gpt-4o" }] }
 * const merged = mergeCodayConfigs(userConfig, projectConfig)
 * // Result:
 * // {
 * //   version: 1,
 * //   context: "I am a developer\n\n---\n\n This is a TypeScript project",
 * //   ai: [{ name: "openai", apiKey: "sk-xxx", model: "gpt-4o" }]
 * // }
 * ```
 */
export function mergeCodayConfigs(...configs: (CodayConfig | undefined | null)[]): CodayConfig {
  const result: CodayConfig = { version: 1 }

  for (const config of configs) {
    if (!config) continue

    // Merge context (concatenate with separator)
    if (config.context) {
      if (result.context) {
        result.context += '\n\n---\n\n' + config.context
      } else {
        result.context = config.context
      }
    }

    // Merge AI providers (by name)
    if (config.ai) {
      if (!result.ai) result.ai = []
      for (const provider of config.ai) {
        const existing = result.ai.find((p) => p.name === provider.name)
        if (existing) {
          // Save existing models before merging provider
          const existingModels = existing.models || []

          // Merge provider properties (will overwrite models)
          Object.assign(existing, provider)

          // Merge models if present
          if (provider.models || existingModels.length > 0) {
            existing.models = [...existingModels] // Start with existing models
            const newModels = provider.models || []

            for (const model of newModels) {
              const existingModel = existing.models.find((m) => m.name === model.name)
              if (existingModel) {
                // Merge model properties
                Object.assign(existingModel, model)
              } else {
                existing.models.push({ ...model })
              }
            }
          }
        } else {
          result.ai.push({ ...provider })
        }
      }
    }

    // Merge MCP config
    if (config.mcp) {
      if (!result.mcp) result.mcp = { servers: [] }
      if (config.mcp.servers) {
        if (!result.mcp.servers) result.mcp.servers = []
        for (const server of config.mcp.servers) {
          const existing = result.mcp.servers.find((s) => s.id === server.id)
          if (existing) {
            Object.assign(existing, server)
          } else {
            result.mcp.servers.push({ ...server })
          }
        }
      }
    }

    // Merge integrations (by key)
    if (config.integrations) {
      if (!result.integrations) result.integrations = {}
      for (const [key, integration] of Object.entries(config.integrations)) {
        if (result.integrations[key]) {
          Object.assign(result.integrations[key], integration)
        } else {
          result.integrations[key] = { ...integration }
        }
      }
    }

    // Simple overrides
    if (config.defaultAgent !== undefined) result.defaultAgent = config.defaultAgent
  }

  return result
}
