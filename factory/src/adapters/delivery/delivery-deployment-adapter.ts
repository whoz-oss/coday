/**
 * Deployment and verification adapter contracts for delivery operations.
 *
 * Adapters are the only bridge to real deployment systems. Their outcomes are
 * normalized against a strict vocabulary; unconfigured adapters report a
 * stable `DELIVERY_ADAPTER_NOT_CONFIGURED` code instead of throwing.
 *
 * Authority: this TypeScript source is bundled into
 * `factory/runtime/factory-operational.mjs`;
 * `factory/lib/delivery-deployment-adapter.mjs` is a stateless compatibility
 * facade re-exporting from that bundle.
 */

const SAFE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/

/** Outcome states an adapter may report. */
export const DELIVERY_ADAPTER_OUTCOMES = Object.freeze(['running', 'succeeded', 'failed', 'indeterminate'] as const)

/** One of the outcome states an adapter may report. */
export type DeliveryAdapterOutcome = (typeof DELIVERY_ADAPTER_OUTCOMES)[number]

/** Result of normalizing a raw adapter outcome. */
export type DeliveryAdapterOutcomeNormalization =
  | { ok: true; value: Readonly<Record<string, unknown>> }
  | { ok: false; error: { code: 'INVALID_DELIVERY_ADAPTER_OUTCOME'; path?: string } }

/** Validates and freezes a raw adapter outcome. */
export function normalizeDeliveryAdapterOutcome(value: unknown): DeliveryAdapterOutcomeNormalization {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).some((k) => !['state', 'correlationRef', 'resultRef', 'errorCode', 'observedAt'].includes(k)) ||
    !(DELIVERY_ADAPTER_OUTCOMES as readonly string[]).includes((value as Record<string, unknown>).state as string)
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_ADAPTER_OUTCOME' } }
  const candidate = value as Record<string, unknown>
  for (const key of ['correlationRef', 'resultRef', 'errorCode'])
    if (candidate[key] !== undefined && !SAFE.test(candidate[key] as string))
      return { ok: false, error: { code: 'INVALID_DELIVERY_ADAPTER_OUTCOME', path: key } }
  if (
    candidate.observedAt !== undefined &&
    (!Number.isFinite(Date.parse(candidate.observedAt as string)) ||
      new Date(Date.parse(candidate.observedAt as string)).toISOString() !== candidate.observedAt)
  )
    return { ok: false, error: { code: 'INVALID_DELIVERY_ADAPTER_OUTCOME', path: 'observedAt' } }
  return { ok: true, value: Object.freeze({ ...candidate }) }
}

/** Providers must honor the same operationId idempotently. Call inspect before replaying running or indeterminate operations. */
export class DeliveryDeploymentAdapter {
  async deploy(..._args: unknown[]): Promise<unknown> {
    throw new Error('Not implemented')
  }
  async rollback(..._args: unknown[]): Promise<unknown> {
    throw new Error('Not implemented')
  }
  async inspect(..._args: unknown[]): Promise<unknown> {
    throw new Error('Not implemented')
  }
  async reconcile(operation: unknown): Promise<unknown> {
    return this.inspect(operation)
  }
}

/** Verification adapter contract (production and rollback verification). */
export class DeliveryVerificationAdapter {
  async verify(..._args: unknown[]): Promise<unknown> {
    throw new Error('Not implemented')
  }
  async inspect(..._args: unknown[]): Promise<unknown> {
    throw new Error('Not implemented')
  }
}

const blocked = async (): Promise<{ ok: false; error: { code: 'DELIVERY_ADAPTER_NOT_CONFIGURED' } }> => ({
  ok: false,
  error: { code: 'DELIVERY_ADAPTER_NOT_CONFIGURED' },
})

/** Deployment adapter used when no provider is configured: every call is blocked. */
export class UnconfiguredDeliveryDeploymentAdapter {
  deploy = blocked
  rollback = blocked
  inspect = blocked
  reconcile = blocked
}

/** Verification adapter used when no provider is configured: every call is blocked. */
export class UnconfiguredDeliveryVerificationAdapter {
  verify = blocked
  inspect = blocked
}
