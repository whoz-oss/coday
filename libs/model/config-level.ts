/**
 * Configuration levels in the hierarchical configuration system
 * Defines the precedence and editability of configurations
 */
export enum ConfigLevel {
  /**
   * Project-specific configuration
   * Allows editing and overrides user-level config
   */
  PROJECT = 'project',

  /**
   * User-specific configuration
   * Allows editing and is the default configuration level
   */
  USER = 'user',

  /**
   * Global (coday.yaml) configuration.
   * Read-only, never editable from CLI, only merged for display or precedence.
   */
  CODAY = 'coday',
  // Note: CODAY is used for merging/display only; not editable from CLI.

}

const ALLOWED_LEVELS = [ConfigLevel.PROJECT, ConfigLevel.USER]

/**
 * Utility to validate and restrict configuration levels
 */
export class ConfigLevelValidator {
  /**
   * Validate that the provided level is an allowed editable level
   * @throws Error if an invalid level is provided
   */
  static validate(level: ConfigLevel): void {
    if (!ALLOWED_LEVELS.includes(level)) {
      throw new Error(`Invalid configuration level: ${level}. Only PROJECT and USER levels are allowed.`)
    }
  }
}
