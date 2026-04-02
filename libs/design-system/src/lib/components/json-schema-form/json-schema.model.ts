/**
 * Minimal JSON Schema subset consumed by ds-json-schema-form.
 *
 * Covers JSON Schema Draft-07 features that map to renderable form fields.
 * Properties not listed here are ignored by the renderer but still passed
 * through to Ajv for validation.
 */
export type JsonSchemaType = 'string' | 'number' | 'integer' | 'boolean' | 'object' | 'array' | 'null'

export interface JsonSchemaObject {
  type?: JsonSchemaType | JsonSchemaType[]
  title?: string
  description?: string
  properties?: Record<string, JsonSchemaObject>
  required?: string[]
  enum?: unknown[]
  format?: string
  default?: unknown
  minimum?: number
  maximum?: number
  minLength?: number
  maxLength?: number
  // Allow any additional JSON Schema keywords (format, pattern, etc.)
  // They won't drive rendering but are forwarded to Ajv.
  [key: string]: unknown
}
