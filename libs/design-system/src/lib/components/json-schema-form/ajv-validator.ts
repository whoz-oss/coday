/**
 * Thin wrapper around Ajv v8 for JSON Schema validation.
 *
 * Ajv v8 ships as a proper ESM/CJS dual package — the default import IS the
 * constructor, so `new Ajv()` works cleanly with esbuild and TypeScript.
 *
 * One shared instance is used (schemas are cached internally by Ajv).
 */
import Ajv from 'ajv'

// `unknownFormats: 'ignore'` prevents Ajv from throwing on JSON Schema
// format keywords it doesn't know (e.g. "password", "uri", "email").
// Format validation is cosmetic for our use case — structural type checking
// (string/number/boolean/required) is what matters here.
const ajv = new Ajv({ allErrors: true, unknownFormats: 'ignore' })

/**
 * Validate `data` against `schema` using Ajv v8.
 * Returns an empty array when valid, or human-readable error strings.
 */
export function validateJsonSchema(schema: object, data: unknown): string[] {
  const valid = ajv.validate(schema, data)
  if (valid) return []
  // Cast via unknown: TypeScript may resolve ajv v6 types from a transitive
  // node_modules entry. Ajv v8 ErrorObject always has instancePath + params.
  const errors = (ajv.errors ?? []) as unknown as Array<{
    instancePath?: string
    keyword?: string
    message?: string
    params?: Record<string, unknown>
  }>
  return errors.map((e) => {
    // `required` errors: instancePath is empty, the missing field name is in params
    if (e.keyword === 'required' && e.params?.['missingProperty']) {
      const field = String(e.params['missingProperty'])
      return `${field}: is required`
    }
    // All other errors: strip the leading slash from instancePath for readability
    const path = e.instancePath ? e.instancePath.replace(/^\//, '') : '(root)'
    return `${path}: ${e.message ?? 'invalid'}`
  })
}
