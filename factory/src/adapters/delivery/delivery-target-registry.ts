/**
 * Trusted delivery-target registry.
 *
 * Targets come only from trusted configuration: each definition is validated,
 * deduplicated by id and bound to a canonical `targetHash` that operations
 * and policies use to detect any target drift.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`;
 * `factory/lib/delivery-target-registry.mjs` is a stateless compatibility
 * facade re-exporting from that bundle.
 */

import { canonicalDeliveryHash } from '../../domain/delivery/delivery-operation-definition.js'

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/,
  DIGEST = /^sha256:[0-9a-f]{64}$/i
const FIELDS = [
  'targetId',
  'environmentKind',
  'adapterId',
  'adapterTargetRef',
  'supportsRollback',
  'verificationSuiteId',
  'verificationSuiteHash',
]

/** A validated, frozen delivery target with its canonical hash. */
export interface DeliveryTarget {
  targetId: string
  environmentKind: string
  adapterId: string
  adapterTargetRef: string
  supportsRollback: boolean
  verificationSuiteId?: string
  verificationSuiteHash?: string
  targetHash: string
}

/** Result of a target lookup. */
export type DeliveryTargetLookup = { ok: true; target: DeliveryTarget } | { ok: false; error: { code: string } }

/** The lookup surface a target registry exposes. */
export interface DeliveryTargetRegistryLike {
  lookup(targetId: unknown): DeliveryTargetLookup
}

const unavailable = (): DeliveryTargetLookup => ({ ok: false, error: { code: 'DELIVERY_TARGET_REGISTRY_UNAVAILABLE' } })

export class DeliveryTargetRegistry implements DeliveryTargetRegistryLike {
  #targets: Map<string, DeliveryTarget> | null

  constructor(definitions?: unknown[]) {
    this.#targets = null
    if (definitions === undefined) return
    if (!Array.isArray(definitions))
      throw Object.assign(new Error('Invalid target registry'), { code: 'INVALID_DELIVERY_TARGET_REGISTRY' })
    const map = new Map<string, DeliveryTarget>()
    for (const raw of definitions as Array<Record<string, unknown>>) {
      if (
        !raw ||
        Object.keys(raw).some((k) => !FIELDS.includes(k)) ||
        !SAFE.test((raw.targetId ?? '') as string) ||
        !['development', 'staging', 'production'].includes(raw.environmentKind as string) ||
        !SAFE.test((raw.adapterId ?? '') as string) ||
        !SAFE.test((raw.adapterTargetRef ?? '') as string) ||
        typeof raw.supportsRollback !== 'boolean' ||
        (raw.verificationSuiteId !== undefined && !SAFE.test(raw.verificationSuiteId as string)) ||
        (raw.verificationSuiteHash !== undefined && !DIGEST.test(raw.verificationSuiteHash as string))
      )
        throw Object.assign(new Error('Invalid target'), { code: 'INVALID_DELIVERY_TARGET' })
      if (map.has(raw.targetId as string))
        throw Object.assign(new Error('Duplicate target'), { code: 'DUPLICATE_DELIVERY_TARGET_ID' })
      const targetHash = canonicalDeliveryHash(raw)
      if ([...map.values()].some((v) => v.targetHash === targetHash))
        throw Object.assign(new Error('Ambiguous target hash'), { code: 'AMBIGUOUS_DELIVERY_TARGET_HASH' })
      map.set(raw.targetId as string, Object.freeze({ ...raw, targetHash }) as DeliveryTarget)
    }
    this.#targets = map
  }
  lookup(targetId: unknown): DeliveryTargetLookup {
    if (!this.#targets) return unavailable()
    if (!SAFE.test((targetId ?? '') as string)) return { ok: false, error: { code: 'DELIVERY_TARGET_NOT_FOUND' } }
    const target = this.#targets.get(targetId as string)
    return target ? { ok: true, target } : { ok: false, error: { code: 'DELIVERY_TARGET_NOT_FOUND' } }
  }
}

/** A registry that is never configured: every lookup reports unavailability. */
export const unavailableDeliveryTargetRegistry: DeliveryTargetRegistryLike = Object.freeze({ lookup: unavailable })
