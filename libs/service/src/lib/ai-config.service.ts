import { UserService } from './user.service'
import { ProjectStateService } from './project-state.service'
import { ConfigLevel, ConfigLevelValidator } from '@coday/model'
import { AiProviderConfig } from '@coday/model'
import { CommandContext } from '@coday/handler'
import { AiModel } from '@coday/model'

/**
 * Represents the combined AI configuration from all levels
 */
export interface AiConfiguration {
  /**
   * All available providers after merging configurations
   */
  providers: AiProviderConfig[]

  /**
   * Mapping of model aliases to their provider and model name
   * For quick lookups when resolving model references
   */
  modelAliasMap: Map<string, { provider: string; model: string }>
}

/**
 * Service for managing AI provider configurations at all levels:
 * - coday.yaml (global defaults)
 * - Project level (project-specific settings)
 * - User level (user-specific settings)
 */
export class AiConfigService {
  private providerCache: Map<ConfigLevel, AiProviderConfig[]> = new Map()
  private mergedConfig: AiConfiguration | null = null

  constructor(
    private userService: UserService,
    private projectService: ProjectStateService
  ) {}

  /**
   * Initialize the service with the current context
   */
  initialize(context: CommandContext): void {
    this.providerCache.clear()
    this.mergedConfig = null

    // Cache configurations at each level for faster access
    this.providerCache.set(ConfigLevel.CODAY, context.project.ai || [])
    this.providerCache.set(ConfigLevel.PROJECT, this.projectService.selectedProject?.config?.ai || [])
    this.providerCache.set(ConfigLevel.USER, this.userService.config.ai || [])
  }

  /**
   * Get all AI provider configurations at a specific level
   */
  getProviders(level: ConfigLevel): AiProviderConfig[] {
    return this.providerCache.get(level) || []
  }

  /**
   * Get a specific AI provider configuration
   */
  getProvider(name: string, level: ConfigLevel): AiProviderConfig | undefined {
    ConfigLevelValidator.validate(level)
    const providers = this.getProviders(level)
    return providers.find((p) => p.name === name)
  }

  /**
   * Save an AI provider configuration
   */
  async saveProvider(config: AiProviderConfig, level: ConfigLevel): Promise<void> {
    ConfigLevelValidator.validate(level)
    const providers = this.getProviders(level)
    const index = providers.findIndex((p) => p.name === config.name)

    if (index >= 0) {
      providers[index] = config
    } else {
      providers.push(config)
    }

    await this.saveProviders(providers, level)
    this.mergedConfig = null // Invalidate cache
  }

  /**
   * Delete an AI provider configuration
   */
  async deleteProvider(name: string, level: ConfigLevel): Promise<void> {
    ConfigLevelValidator.validate(level)
    const providers = this.getProviders(level)
    const filtered = providers.filter((p) => p.name !== name)

    if (filtered.length !== providers.length) {
      await this.saveProviders(filtered, level)
      this.mergedConfig = null // Invalidate cache
    }
  }

  /**
   * Get a specific model configuration
   */
  getModel(providerName: string, modelName: string, level: ConfigLevel): AiModel | undefined {
    ConfigLevelValidator.validate(level)
    const provider = this.getProvider(providerName, level)
    return provider?.models?.find((m) => m.name === modelName || m.alias === modelName)
  }

  /**
   * Save a model configuration for a specific provider
   */
  async saveModel(providerName: string, model: AiModel, level: ConfigLevel): Promise<void> {
    ConfigLevelValidator.validate(level)
    let provider = this.getProvider(providerName, level)

    if (!provider) {
      // Create provider if it doesn't exist
      provider = { name: providerName, models: [] }
      await this.saveProvider(provider, level)
    }

    const models = provider.models || []
    const index = models.findIndex((m) => m.name === model.name || (model.alias && m.alias === model.alias))

    if (index >= 0) {
      models[index] = model
    } else {
      models.push(model)
    }

    provider.models = models
    await this.saveProvider(provider, level)
    this.mergedConfig = null // Invalidate cache
  }

  /**
   * Delete a model configuration
   */
  async deleteModel(providerName: string, modelName: string, level: ConfigLevel): Promise<void> {
    ConfigLevelValidator.validate(level)
    const provider = this.getProvider(providerName, level)

    if (provider?.models) {
      const filtered = provider.models.filter((m) => m.name !== modelName && m.alias !== modelName)

      if (filtered.length !== provider.models.length) {
        provider.models = filtered
        await this.saveProvider(provider, level)
        this.mergedConfig = null // Invalidate cache
      }
    }
  }

  /**
   * Get the merged configuration from all levels
   */
  getMergedConfiguration(): AiConfiguration {
    if (this.mergedConfig) {
      return this.mergedConfig
    }

    // Create a new merged configuration
    const merged: AiProviderConfig[] = []
    const modelAliasMap = new Map<string, { provider: string; model: string }>()

    // Process in order of precedence: coday -> project -> user
    for (const level of [ConfigLevel.CODAY, ConfigLevel.PROJECT, ConfigLevel.USER]) {
      const providers = this.providerCache.get(level) || []

      for (const provider of providers) {
        const existingIndex = merged.findIndex((p) => p.name === provider.name)

        if (existingIndex >= 0) {
          // Merge with existing provider
          const existing = merged[existingIndex]!

          // Merge provider-level properties
          merged[existingIndex] = {
            ...existing,
            ...provider,
          }

          // Merge models
          if (provider.models?.length) {
            const mergedModels = existing.models || []

            for (const model of provider.models) {
              const modelIndex = mergedModels.findIndex(
                (m) => m.name === model.name || (model.alias && m.alias === model.alias)
              )

              if (modelIndex >= 0) {
                // Update existing model
                mergedModels[modelIndex] = {
                  ...mergedModels[modelIndex],
                  ...model,
                  price: { ...mergedModels[modelIndex]?.price, ...model.price },
                }
              } else {
                // Add new model
                mergedModels.push(model)
              }

              // Update alias map
              if (model.alias) {
                modelAliasMap.set(model.alias, {
                  provider: provider.name,
                  model: model.name,
                })
              }
            }

            merged[existingIndex].models = mergedModels
          }
        } else {
          // Add new provider
          merged.push({ ...provider })

          // Add model aliases to map
          provider.models?.forEach((model) => {
            if (model.alias) {
              modelAliasMap.set(model.alias, {
                provider: provider.name,
                model: model.name,
              })
            }
          })
        }
      }
    }

    this.mergedConfig = { providers: merged, modelAliasMap }
    return this.mergedConfig
  }

  /**
   * Resolve a model alias to a specific provider and model
   */
  resolveModelAlias(alias: string): { provider: string; model: string } | undefined {
    return this.getMergedConfiguration().modelAliasMap.get(alias)
  }

  /**
   * Save providers at a specific level
   * @private
   */
  private async saveProviders(providers: AiProviderConfig[], level: ConfigLevel): Promise<void> {
    switch (level) {
      case ConfigLevel.PROJECT:
        if (!this.projectService.selectedProject) {
          throw new Error('No project selected')
        }

        // Update project config
        const updatedProjectConfig = {
          ...this.projectService.selectedProject.config,
          ai: providers,
        }

        this.projectService.save(updatedProjectConfig)
        this.providerCache.set(level, providers)
        break

      case ConfigLevel.USER:
        // Update user config
        this.userService.config.ai = providers
        this.userService.save()
        this.providerCache.set(level, providers)
        break
    }
  }
}
