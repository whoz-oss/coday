/**
 * Simple utility for managing user preferences in the browser
 */

const STORAGE_KEY = 'coday-preferences';

/**
 * Get a user preference
 */
export function getPreference<T>(key: string, defaultValue?: T): T | undefined {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (!stored) return defaultValue;
    
    const preferences = JSON.parse(stored);
    return preferences[key] !== undefined ? preferences[key] : defaultValue;
  } catch {
    return defaultValue;
  }
}

/**
 * Set a user preference
 */
export function setPreference<T>(key: string, value: T): void {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    const preferences = stored ? JSON.parse(stored) : {};
    
    preferences[key] = value;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(preferences));
  } catch (error) {
    console.error('Failed to set preference:', error);
  }
}