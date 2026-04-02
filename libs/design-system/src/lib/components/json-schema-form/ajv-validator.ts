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
  // ajv v8 ErrorObject has `instancePath`; cast via unknown to avoid
  // TypeScript resolving the v6 types from the wrong node_modules entry.
  const errors = (ajv.errors ?? []) as unknown as Array<{ instancePath?: string; message?: string }>
  return errors.map((e) => `${e.instancePath || '(root)'}: ${e.message ?? 'invalid'}`)
}
