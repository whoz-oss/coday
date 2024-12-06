import { DEFAULT_USER_CONFIG, UserConfig } from '../model/user-config'
import { BehaviorSubject, Observable } from 'rxjs'
import path from 'path'
import { existsSync } from 'fs'
import { mkdirSync } from 'node:fs'
import { readYamlFile } from './read-yaml-file'
import { writeYamlFile } from './write-yaml-file'
// Create singleton instance using same path as config service
import { configService } from './config.service'

const USER_CONFIG_FILENAME = 'user.yml'

export class UserConfigService {
  private config: UserConfig | null = null
  private readonly configSubject = new BehaviorSubject<UserConfig | null>(null)
  readonly config$: Observable<UserConfig | null> = this.configSubject.asObservable()

  constructor(private readonly configPath: string) {
    this.initConfig()
  }

  private initConfig(): void {
    const configFilePath: string = path.join(this.configPath, USER_CONFIG_FILENAME)

    // Create directory if it doesn't exist
    if (!existsSync(this.configPath)) {
      mkdirSync(this.configPath, { recursive: true })
    }

    // Create config file if it doesn't exist
    if (!existsSync(configFilePath)) {
      writeYamlFile(configFilePath, DEFAULT_USER_CONFIG)
      this.updateConfig(DEFAULT_USER_CONFIG)
    } else {
      // Read config
      const config = readYamlFile<UserConfig>(configFilePath)
      if (config) {
        this.updateConfig(config)
      }
    }
  }

  updateConfig(update: Partial<UserConfig>): void {
    this.config = { ...(this.config || DEFAULT_USER_CONFIG), ...update }
    this.configSubject.next(this.config)

    writeYamlFile(path.join(this.configPath, USER_CONFIG_FILENAME), this.config)
  }

  get currentConfig(): UserConfig | null {
    return this.config
  }
}

export const userConfigService = new UserConfigService(configService.configPath)
