import { UserService } from './user.service'
import { ProjectService } from './project.service'
import { Interactor } from '../model'
import { ConfigMaskingService } from './config-masking.service'

/**
 * Registry for managing UserService and ProjectService instances
 * without relying on ServerClient sessions.
 * 
 * This allows REST API endpoints to access configuration services
 * independently of the SSE session management.
 */
export class ConfigServiceRegistry {
  private maskingService = new ConfigMaskingService()
  private userServices = new Map<string, UserService>()
  private projectServices = new Map<string, ProjectService>()
  
  constructor(
    private configDir: string | undefined,
    private interactor: Interactor
  ) {}
  
  /**
   * Get or create a UserService instance for the given username
   */
  getUserService(username: string): UserService {
    let service = this.userServices.get(username)
    
    if (!service) {
      service = new UserService(this.configDir, username, this.interactor)
      this.userServices.set(username, service)
    }
    
    return service
  }
  
  /**
   * Get or create a ProjectService instance
   * Note: ProjectService is shared across users as it manages project-level config
   */
  getProjectService(): ProjectService {
    const key = 'singleton' // ProjectService is a singleton
    let service = this.projectServices.get(key)
    
    if (!service) {
      service = new ProjectService(this.interactor, this.configDir)
      this.projectServices.set(key, service)
    }
    
    return service
  }
  
  /**
   * Clear a specific user service from cache
   * Useful when we want to reload configuration
   */
  clearUserService(username: string): void {
    this.userServices.delete(username)
  }
  
  /**
   * Clear all cached services
   */
  clearAll(): void {
    this.userServices.clear()
    this.projectServices.clear()
  }
  
  /**
   * Mask sensitive values in configuration before sending to client
   */
  maskConfig<T>(config: T): T {
    return this.maskingService.maskConfig(config)
  }
  
  /**
   * Unmask configuration by comparing with original
   * Preserves original values where masked placeholders exist
   */
  unmaskConfig<T>(incomingConfig: T, originalConfig: T): T {
    return this.maskingService.unmaskConfig(incomingConfig, originalConfig)
  }
}
