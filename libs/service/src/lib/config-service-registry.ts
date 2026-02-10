import { UserService } from './user.service'
import { Interactor } from '@coday/model'
import { sanitizeUsername } from '@coday/utils'

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
   * Username is sanitized before caching to ensure consistency
   */
  getUserService(username: string): UserService {
    // Sanitize username for cache key consistency
    const sanitized = sanitizeUsername(username)
    let service = this.userServices.get(sanitized)

    if (!service) {
      service = new UserService(this.configDir, username, this.interactor)
      this.userServices.set(sanitized, service)
    }

    return service
  }

  /**
   * Clear a specific user service from cache
   * Useful when we want to reload configuration
   * Username is sanitized to match cache key
   */
  clearUserService(username: string): void {
    const sanitized = sanitizeUsername(username)
    this.userServices.delete(sanitized)
  }

  /**
   * Clear all cached services
   */
  clearAll(): void {
    this.userServices.clear()
  }
}
