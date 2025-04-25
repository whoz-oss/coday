/**
 * Simple configuration migration utility
 * Handles sequential version migrations for configuration objects
 */

export type MigrationFunction = (config: any) => any

export interface Migration {
  /**
   * Target version this migration applies to
   * Migration will transform from (version) to (version + 1)
   */
  version: number

  /**
   * Description of what this migration does
   */
  description: string

  /**
   * Migration function that transforms the config
   */
  migrate: MigrationFunction
}

/**
 * Migrates a configuration object through a series of migrations
 *
 * @param data The configuration object to migrate
 * @param migrations Array of migration steps in order
 * @returns migrated data as new reference if it was changed
 */
export function migrateConfig(data: any, migrations: Migration[]): any {
  // If no version, assume version 1
  let currentConfig = data.version ? data : { ...data, version: 1 }

  // Order migrations by version
  const orderedMigrations = [...migrations].sort((a, b) => a.version - b.version)

  // Apply migrations sequentially based on the current version
  for (const migration of orderedMigrations) {
    // Skip migrations that don't apply to our current version
    if (migration.version !== currentConfig.version) {
      continue
    }
    const newConfig = migration.migrate({ ...currentConfig })
    newConfig.version = migration.version + 1
    currentConfig = newConfig
  }

  return currentConfig
}
