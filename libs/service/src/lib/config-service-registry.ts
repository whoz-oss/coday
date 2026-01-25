import { UserService } from './user.service'
import { Interactor } from '@coday/model/interactor'

/**
 * Registry for managing UserService and ProjectService instances
 * without relying on ServerClient sessions.
 *
 * This allows REST API endpoints to access configuration services
 * independently of the SSE session management.
 */
export class ConfigServiceRegistry {
  private readonly userServices = new Map<string, UserService>()

  constructor(
    private readonly configDir: string | undefined,
    private readonly interactor: Interactor
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
  }
}
