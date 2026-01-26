import { Migration } from '@coday/utils'
import { aiPropertyToAi } from './ai-providers-to-ai.migration'

/**
 * Migrations for the user configuration
 * Each migration handles a specific version upgrade
 */
export const userConfigMigrations: Migration[] = [
  aiPropertyToAi,
  // Additional migrations to add here, ordered
]
