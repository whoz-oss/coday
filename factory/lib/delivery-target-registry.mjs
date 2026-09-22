import { canonicalDeliveryHash } from './delivery-operation-definition.mjs'
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
const unavailable = () => ({ ok: false, error: { code: 'DELIVERY_TARGET_REGISTRY_UNAVAILABLE' } })
export class DeliveryTargetRegistry {
  #targets
  constructor(definitions) {
    this.#targets = null
    if (definitions === undefined) return
    if (!Array.isArray(definitions))
      throw Object.assign(new Error('Invalid target registry'), { code: 'INVALID_DELIVERY_TARGET_REGISTRY' })
    const map = new Map()
    for (const raw of definitions) {
      if (
        !raw ||
        Object.keys(raw).some((k) => !FIELDS.includes(k)) ||
        !SAFE.test(raw.targetId ?? '') ||
        !['development', 'staging', 'production'].includes(raw.environmentKind) ||
        !SAFE.test(raw.adapterId ?? '') ||
        !SAFE.test(raw.adapterTargetRef ?? '') ||
        typeof raw.supportsRollback !== 'boolean' ||
        (raw.verificationSuiteId !== undefined && !SAFE.test(raw.verificationSuiteId)) ||
        (raw.verificationSuiteHash !== undefined && !DIGEST.test(raw.verificationSuiteHash))
      )
        throw Object.assign(new Error('Invalid target'), { code: 'INVALID_DELIVERY_TARGET' })
      if (map.has(raw.targetId))
        throw Object.assign(new Error('Duplicate target'), { code: 'DUPLICATE_DELIVERY_TARGET_ID' })
      const targetHash = canonicalDeliveryHash(raw)
      if ([...map.values()].some((v) => v.targetHash === targetHash))
        throw Object.assign(new Error('Ambiguous target hash'), { code: 'AMBIGUOUS_DELIVERY_TARGET_HASH' })
      map.set(raw.targetId, Object.freeze({ ...raw, targetHash }))
    }
    this.#targets = map
  }
  lookup(targetId) {
    if (!this.#targets) return unavailable()
    if (!SAFE.test(targetId ?? '')) return { ok: false, error: { code: 'DELIVERY_TARGET_NOT_FOUND' } }
    const target = this.#targets.get(targetId)
    return target ? { ok: true, target } : { ok: false, error: { code: 'DELIVERY_TARGET_NOT_FOUND' } }
  }
}
export const unavailableDeliveryTargetRegistry = Object.freeze({ lookup: unavailable })
