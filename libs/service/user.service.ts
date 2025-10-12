import * as path from 'node:path'
import { existsSync, mkdirSync } from 'fs'
import { readYamlFile } from './read-yaml-file'
import { writeYamlFile } from './write-yaml-file'
import { DEFAULT_USER_CONFIG, UserConfig } from '../model/user-config'
import { UserData } from '../model/user-data'
import { IntegrationLocalConfig, Interactor } from '../model'
import * as os from 'node:os'
import { migrateData } from '../utils/data-migration'
import { userConfigMigrations } from './migration/user-config-migrations'
import { ConfigMaskingService } from './config-masking.service'

const usersFolder = 'users'
const USER_FILENAME = 'user.yaml'

export class UserService {
  public userConfigPath: string
  readonly sanitizedUsername: string
  config: UserConfig
  private maskingService = new ConfigMaskingService()

  constructor(
    codayConfigPath: string | undefined,
    public readonly username: string,
    private interactor: Interactor
  ) {
    // Format username correctly
    this.sanitizedUsername = this.sanitizeUsername(username)

    // Resolve configuration path
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    const usersPath = path.join(codayConfigPath ?? defaultConfigPath, usersFolder)
    this.userConfigPath = path.join(usersPath, this.sanitizedUsername)

    // Ensure the user's directory exists
    mkdirSync(this.userConfigPath, { recursive: true })

    // Load user configuration
    const filePath = path.join(this.userConfigPath, USER_FILENAME)
    if (!existsSync(filePath)) {
      // Add version to default config
      const defaultConfig = { ...DEFAULT_USER_CONFIG, version: 1 }
      writeYamlFile(filePath, defaultConfig)
    }

    const rawUserConfig = readYamlFile(filePath)

    if (!rawUserConfig) {
      throw Error(`Could not read user config for username ${this.sanitizedUsername}`)
    }

    // Apply migrations
    this.config = migrateData(rawUserConfig, userConfigMigrations)

    // Save the migrated config if reference changed, meaning some migration happened
    if (this.config !== rawUserConfig) {
      writeYamlFile(filePath, this.config)
      this.interactor.displayText(`User configuration migrated to version ${this.config?.version}`)
    }
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
    // Ensure projects object exists
    if (!this.config.projects) {
      this.config.projects = {}
    }

    // Create/update project-specific user integrations
    if (!this.config.projects[projectName]) {
      this.config.projects[projectName] = {
        integration: {},
      }
    }

    this.config.projects[projectName].integration = {
      ...this.config.projects[projectName].integration,
      ...integrations,
    }

    // Save the configuration
    this.save()
  }

  private sanitizeUsername(username: string): string {
    // Replaces non-alphanumeric characters with underscores
    return username.replace(/[^a-zA-Z0-9]/g, '_')
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
    if (!this.config.projects) {
      this.config.projects = {}
    }
    if (!this.config.projects[projectName]) {
      this.config.projects[projectName] = { integration: {} }
    }
    this.config.projects[projectName].bio = bio?.trim() || undefined
    this.save()
  }

  public getProjectBio(projectName: string): string | undefined {
    return this.config.projects?.[projectName]?.bio
  }

  // Combined bio (USER + PROJECT)
  public getCombinedBio(projectName?: string): string | undefined {
    const userBio = this.getBio()
    const projectBio = projectName ? this.getProjectBio(projectName) : undefined
    if (userBio && projectBio) {
      return `${userBio}\n\n    Project context: ${projectBio}`
    }
    return projectBio || userBio
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
