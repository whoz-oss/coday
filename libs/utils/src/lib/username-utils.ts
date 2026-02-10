/**
 * Username sanitization utilities for filesystem operations
 *
 * This module provides centralized username processing to ensure
 * consistent directory and file naming across the entire application.
 */

/**
 * Sanitize username for filesystem usage
 *
 * Replaces all non-alphanumeric characters with underscores
 * to ensure consistent directory/file naming across platforms.
 * This prevents issues with special characters in filesystem paths.
 *
 * Examples:
 * - "Jean-Pierre" → "Jean_Pierre"
 * - "user@example.com" → "user_example_com"
 * - "John_Doe" → "John_Doe" (unchanged)
 * - "José.García" → "Jos__Garc_a"
 *
 * @param username Raw username (from email header or system)
 * @returns Sanitized username safe for filesystem operations
 */
export function sanitizeUsername(username: string): string {
  return username.replace(/[^a-zA-Z0-9]/g, '_')
}
