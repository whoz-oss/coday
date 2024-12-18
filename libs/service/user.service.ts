import * as path from 'node:path'
import { existsSync, mkdirSync } from 'fs'
import { readYamlFile } from './read-yaml-file'
import { writeYamlFile } from './write-yaml-file'
import { DEFAULT_USER_CONFIG, UserConfig } from '../model/user-config'
import { IntegrationLocalConfig } from '../model'
import * as os from 'node:os'

const usersFolder = 'users'
const USER_FILENAME = 'user.yaml'

export class UserService {
  public userConfigPath: string
  readonly sanitizedUsername: string
  config: UserConfig

  constructor(
    codayConfigPath: string | undefined,
    public readonly username: string
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
      writeYamlFile(filePath, DEFAULT_USER_CONFIG)
    }

    const userConfig: UserConfig | undefined = readYamlFile<UserConfig>(filePath)

    if (!userConfig) {
      throw Error(`Could not read user config for username ${this.sanitizedUsername}`)
    }
    this.config = userConfig
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
}
