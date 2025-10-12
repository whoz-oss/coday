/**
 * Simple service for masking sensitive configuration values
 * Uses field name heuristics to identify sensitive data
 */
export class ConfigMaskingService {
  private readonly MASK_PATTERN = '****'
  
  // Field names that should be masked (case-insensitive)
  private readonly SENSITIVE_FIELD_NAMES = [
    'apikey',
    'api_key',
    'password',
    'token',
    'secret',
    'auth'
  ]
  
  /**
   * Check if a field name indicates sensitive data
   */
  private isSensitiveField(fieldName: string): boolean {
    const lower = fieldName.toLowerCase()
    return this.SENSITIVE_FIELD_NAMES.some(sensitive => lower.includes(sensitive))
  }
  
  /**
   * Mask a single value
   * Shows first 4 and last 4 characters: "abcdefghijkl" => "abcd****ijkl"
   * For short values (< 12 chars), uses partial masking
   */
  private maskValue(value: string): string {
    if (!value || value.length === 0) {
      return value
    }
    
    // For very short values (8 or less), mask completely
    if (value.length <= 8) {
      return this.MASK_PATTERN
    }
    
    // For values 9-11 chars, show first 2 and last 2
    if (value.length < 12) {
      const first = value.substring(0, 2)
      const last = value.substring(value.length - 2)
      return `${first}${this.MASK_PATTERN}${last}`
    }
    
    // For 12+ chars, show first 4 and last 4
    const first = value.substring(0, 4)
    const last = value.substring(value.length - 4)
    return `${first}${this.MASK_PATTERN}${last}`
  }
  
  /**
   * Check if a value contains the mask pattern
   */
  private isMasked(value: any): boolean {
    return typeof value === 'string' && value.includes(this.MASK_PATTERN)
  }
  
  /**
   * Recursively mask sensitive fields in configuration
   * Returns a deep clone with sensitive values masked
   */
  maskConfig<T>(config: T): T {
    if (config === null || config === undefined) {
      return config
    }
    
    // Handle primitives
    if (typeof config !== 'object') {
      return config
    }
    
    // Handle arrays
    if (Array.isArray(config)) {
      return config.map(item => this.maskConfig(item)) as any
    }
    
    // Handle objects
    const masked: any = {}
    
    for (const [key, value] of Object.entries(config)) {
      if (this.isSensitiveField(key) && typeof value === 'string') {
        // Mask sensitive string fields
        masked[key] = this.maskValue(value)
      } else if (key === 'env' && typeof value === 'object' && value !== null && !Array.isArray(value)) {
        // Special handling for 'env' objects - mask all values
        masked[key] = this.maskEnvObject(value)
      } else if (typeof value === 'object' && value !== null) {
        // Recursively process nested objects/arrays
        masked[key] = this.maskConfig(value)
      } else {
        // Keep non-sensitive primitives as-is
        masked[key] = value
      }
    }
    
    return masked as T
  }
  
  /**
   * Mask all values in an env object (used for MCP server env variables)
   */
  private maskEnvObject(env: Record<string, any>): Record<string, any> {
    const masked: Record<string, any> = {}
    
    for (const [key, value] of Object.entries(env)) {
      if (typeof value === 'string') {
        masked[key] = this.maskValue(value)
      } else {
        masked[key] = value
      }
    }
    
    return masked
  }
  
  /**
   * Unmask configuration by merging incoming config with original
   * - If incoming value is masked placeholder, use original value (no change)
   * - If incoming value is different, use incoming value (user changed it)
   * - If incoming value is missing but exists in original, keep original (preserve data)
   */
  unmaskConfig<T>(incomingConfig: T, originalConfig: T): T {
    if (incomingConfig === null || incomingConfig === undefined) {
      return incomingConfig
    }
    
    if (originalConfig === null || originalConfig === undefined) {
      return incomingConfig
    }
    
    // Handle primitives
    if (typeof incomingConfig !== 'object') {
      return incomingConfig
    }
    
    // Handle arrays - use incoming array as-is (no merging)
    if (Array.isArray(incomingConfig)) {
      return incomingConfig.map((item, index) => {
        const originalItem = Array.isArray(originalConfig) ? originalConfig[index] : undefined
        return this.unmaskConfig(item, originalItem)
      }) as any
    }
    
    // Handle objects
    const unmasked: any = {}
    const incoming = incomingConfig as any
    const original = originalConfig as any
    
    // Process all keys from incoming config
    for (const [key, incomingValue] of Object.entries(incoming)) {
      const originalValue = original[key]
      
      if (this.isSensitiveField(key) && typeof incomingValue === 'string') {
        // Sensitive field handling
        if (this.isMasked(incomingValue)) {
          // Masked placeholder - keep original value
          unmasked[key] = originalValue
        } else {
          // New value - use it
          unmasked[key] = incomingValue
        }
      } else if (key === 'env' && typeof incomingValue === 'object' && incomingValue !== null && !Array.isArray(incomingValue)) {
        // Special handling for 'env' objects
        unmasked[key] = this.unmaskEnvObject(incomingValue, originalValue)
      } else if (typeof incomingValue === 'object' && incomingValue !== null) {
        // Recursively process nested objects/arrays
        unmasked[key] = this.unmaskConfig(incomingValue, originalValue)
      } else {
        // Non-sensitive primitives - use incoming value
        unmasked[key] = incomingValue
      }
    }
    
    // Preserve keys from original that are missing in incoming (but only for objects, not arrays)
    if (!Array.isArray(incomingConfig)) {
      for (const [key, originalValue] of Object.entries(original)) {
        if (!(key in incoming)) {
          unmasked[key] = originalValue
        }
      }
    }
    
    return unmasked as T
  }
  
  /**
   * Unmask env object by merging with original
   */
  private unmaskEnvObject(incomingEnv: Record<string, any>, originalEnv: any): Record<string, any> {
    if (!originalEnv || typeof originalEnv !== 'object') {
      return incomingEnv
    }
    
    const unmasked: Record<string, any> = {}
    
    // Process incoming env vars
    for (const [key, incomingValue] of Object.entries(incomingEnv)) {
      if (typeof incomingValue === 'string' && this.isMasked(incomingValue)) {
        // Masked - use original value
        unmasked[key] = originalEnv[key]
      } else {
        // New or changed value
        unmasked[key] = incomingValue
      }
    }
    
    // Preserve original env vars not in incoming
    for (const [key, originalValue] of Object.entries(originalEnv)) {
      if (!(key in incomingEnv)) {
        unmasked[key] = originalValue
      }
    }
    
    return unmasked
  }
}
