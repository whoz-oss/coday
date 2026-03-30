import * as path from 'node:path'
import { existsSync, mkdirSync } from 'fs'
import { DEFAULT_USER_CONFIG, UserConfig } from '@coday/model'
import { UserData } from '@coday/model'
import { Interactor } from '@coday/model'
import { IntegrationLocalConfig } from '@coday/model'
import * as os from 'node:os'
import { migrateData, readYamlFile, sanitizeUsername, userConfigMigrations, writeYamlFile } from '@coday/utils'

import { ConfigMaskingService } from './config-masking.service'

const usersFolder = 'users'
const USER_FILENAME = 'user.yaml'

export class UserService {
  public userConfigPath: string
  readonly sanitizedUsername: string
  config: UserConfig
  private readonly maskingService = new ConfigMaskingService()

  constructor(
    codayConfigPath: string | undefined,
    public readonly username: string,
    private readonly interactor: Interactor
  ) {
    // Format username correctly using centralized utility
    this.sanitizedUsername = sanitizeUsername(username)

    // Resolve configuration path
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    const usersPath = path.join(codayConfigPath ?? defaultConfigPath, usersFolder)
    this.userConfigPath = path.join(usersPath, this.sanitizedUsername)

    // Ensure the user's directory exists
    mkdirSync(this.userConfigPath, { recursive: true })

    // Load user configuration
    const filePath = path.join(this.userConfigPath, USER_FILENAME)
    if (!existsSync(filePath)) {
      console.log(`[USER_SERVICE] Creating default config for user '${this.sanitizedUsername}' at ${filePath}`)
      // Store the raw username (email) so GET /api/users can return real emails
      const defaultConfig = { ...DEFAULT_USER_CONFIG, version: 1, username }
      writeYamlFile(filePath, defaultConfig)
    }

    console.log(`[USER_SERVICE] Loading config for '${this.sanitizedUsername}' from ${filePath}`)
    const rawUserConfig = readYamlFile(filePath)

    if (!rawUserConfig) {
      console.log(`[USER_SERVICE] ERROR: Failed to read user config at ${filePath}`)
      throw Error(`Could not read user config for username ${this.sanitizedUsername}`)
    }

    // Apply migrations
    this.config = migrateData(rawUserConfig, userConfigMigrations)

    // Save the migrated config if reference changed, meaning some migration happened
    if (this.config !== rawUserConfig) {
      console.log(`[USER_SERVICE] Config migrated for '${this.sanitizedUsername}' to version ${this.config?.version}`)
      writeYamlFile(filePath, this.config)
      this.interactor.displayText(`User configuration migrated to version ${this.config?.version}`)
    }
  }

  /**
   * Resolves the effective project name for user config lookups.
   * When running inside a git worktree, the project name is suffixed with
   * "__<sanitized-branch>". We strip that suffix so that worktree sub-projects
   * transparently share the parent project's user configuration.
   *
   * Examples:
   *   "my-project"                   → "my-project"
   *   "my-project__feat-my-feature"  → "my-project"
   */
  public resolveProjectName(projectName: string): string {
    const separatorIndex = projectName.lastIndexOf('__')
    return separatorIndex !== -1 ? projectName.substring(0, separatorIndex) : projectName
  }

  public save() {
    if (this.config) {
      const filePath = path.join(this.userConfigPath, USER_FILENAME)
      writeYamlFile(filePath, this.config)
    } else {
      console.error('No user configuration available to save.')
    }
  }

  /**
   * Set integration configurations for a specific project in user-level config
   * @param projectName Name of the project
   * @param integrations Integration configurations to save
   */
  public setProjectIntegration(projectName: string, integrations: IntegrationLocalConfig) {
    const resolvedName = this.resolveProjectName(projectName)
    // Ensure projects object exists
    this.config.projects ??= {}

    // Create/update project-specific user integrations
    this.config.projects[resolvedName] ??= {
      integration: {},
    }

    this.config.projects[resolvedName].integration = {
      ...this.config.projects[resolvedName].integration,
      ...integrations,
    }

    // Save the configuration
    this.save()
  }

  public setBio(bio: string): void {
    this.config.bio = bio?.trim() || undefined
    this.save()
  }

  public getBio(): string | undefined {
    return this.config.bio
  }

  // Project-level bio methods
  public setProjectBio(projectName: string, bio: string): void {
    const resolvedName = this.resolveProjectName(projectName)
    this.config.projects ??= {}
    this.config.projects[resolvedName] ??= { integration: {} }
    this.config.projects[resolvedName].bio = bio?.trim() || undefined
    this.save()
  }

  public getProjectBio(projectName: string): string | undefined {
    return this.config.projects?.[this.resolveProjectName(projectName)]?.bio
  }

  // Combined bio (USER + PROJECT)
  public getCombinedBio(projectName?: string): string | undefined {
    const userBio = this.getBio()
    const projectBio = projectName ? this.getProjectBio(projectName) : undefined
    if (userBio && projectBio) {
      return `${userBio}\n\n    Project context: ${projectBio}`
    }
    return projectBio ?? userBio
  }

  public getUserData(projectName?: string): UserData {
    return {
      username: this.username,
      bio: this.getCombinedBio(projectName),
    }
  }

  /**
   * Get configuration with sensitive values masked for client display
   */
  public getConfigForClient(): UserConfig {
    return this.maskingService.maskConfig(this.config)
  }

  /**
   * Update configuration from client, unmasking to preserve original sensitive values
   */
  public updateConfigFromClient(incomingConfig: UserConfig): void {
    this.config = this.maskingService.unmaskConfig(incomingConfig, this.config)
    this.save()
  }
}
