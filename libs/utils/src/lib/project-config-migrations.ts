import { Migration } from './data-migration'
import { aiPropertyToAi } from './ai-providers-to-ai.migration'

/**
 * Migrations for the project configuration
 * Each migration handles a specific version upgrade
 */
export const projectConfigMigrations: Migration[] = [
  aiPropertyToAi,
  // Additional migrations would be added here as needed
]
