/**
 * Primitive JSON-defensive helpers shared across all mapper modules.
 * These are pure, zero-dependency utilities — no Angular, no models.
 */

export type JsonObject = Record<string, unknown>

export function asObject(value: unknown): JsonObject | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as JsonObject) : undefined
}

export function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : []
}

export function getString(obj: JsonObject | undefined, key: string): string | undefined {
  const value = obj?.[key]
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

export function getNumber(obj: JsonObject | undefined, key: string): number | undefined {
  const value = obj?.[key]
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

export function getBoolean(obj: JsonObject | undefined, key: string): boolean | undefined {
  const value = obj?.[key]
  return typeof value === 'boolean' ? value : undefined
}

/** Coerce an instant (ISO string or epoch ms) to epoch ms, else `null`. */
export function toEpochMs(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Date.parse(value)
    return Number.isFinite(parsed) ? parsed : null
  }
  return null
}

/** Escape-free (UTC) HH:mm:ss clock label for any ISO instant. */
export function formatClock(value?: string): string {
  if (!value) return '--:--:--'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '--:--:--'
  return date.toISOString().slice(11, 19)
}

/** Extract a list from a bare array, a `{ [key]: [...] }` block or `items`/`data` wrappers. */
export function asList(payload: unknown, key: string): unknown[] {
  if (Array.isArray(payload)) return payload
  const obj = asObject(payload)
  if (!obj) return []
  const direct = obj[key]
  if (Array.isArray(direct)) return direct
  if (Array.isArray(obj['items'])) return obj['items'] as unknown[]
  if (Array.isArray(obj['data'])) return obj['data'] as unknown[]
  return []
}
