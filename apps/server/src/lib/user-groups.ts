import { readYamlFile } from '@coday/utils'
import * as path from 'node:path'
import * as os from 'node:os'

/**
 * Check if a user belongs to the CODAY_ADMIN group
 *
 * This function reads the user's configuration file and checks if they have
 * the CODAY_ADMIN group in their groups array.
 *
 * @param username - Username to check
 * @param configDir - Coday configuration directory (defaults to ~/.coday)
 * @returns true if user is in CODAY_ADMIN group, false otherwise
 */
export function isUserAdmin(username: string, configDir?: string): boolean {
  try {
    const defaultConfigPath = path.join(os.userInfo().homedir, '.coday')
    const userConfigPath = path.join(configDir ?? defaultConfigPath, 'users', username, 'user.yml')

    const userConfig = readYamlFile<{ groups?: string[] }>(userConfigPath)

    if (!userConfig) {
      return false
    }

    return userConfig.groups?.includes('CODAY_ADMIN') ?? false
  } catch (error) {
    // If config doesn't exist or can't be read, user is not admin
    console.error(`[USER_GROUPS] Error checking admin status for ${username}:`, error)
    return false
  }
}

/**
 * Check if a user can access a webhook (owner or admin)
 *
 * @param webhookCreatedBy - Username of the webhook creator
 * @param requestingUser - Username of the user requesting access
 * @param configDir - Coday configuration directory (defaults to ~/.coday)
 * @returns true if user can access the webhook, false otherwise
 */
export function canAccessWebhook(webhookCreatedBy: string, requestingUser: string, configDir?: string): boolean {
  // Owner can always access
  if (webhookCreatedBy === requestingUser) {
    return true
  }

  // Admin can access any webhook
  return isUserAdmin(requestingUser, configDir)
}
