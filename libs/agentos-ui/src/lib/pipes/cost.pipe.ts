import { Pipe, PipeTransform } from '@angular/core'

/** What to show for a cost nobody can compute. Never `0`, which reads as "this was free". */
export const UNKNOWN_COST = '—'

/**
 * Below this, two decimals would round every cheap run to `0.00`.
 *
 * One cent is the boundary because that is where two decimals stop carrying information: a run at
 * 0.004 and a run at 0.0004 both render as `0.00`, and an order of magnitude is exactly what the
 * reader of a cost figure is looking for.
 */
const CENT = 0.01

/**
 * Formats an estimated cost for display.
 *
 * Two decimals is what the figures are read for — comparing runs, noticing an expensive one — and
 * the six that the `number: '1.2-6'` pipe used to print made every panel look like a measurement
 * instrument. Four decimals under a cent keep a sub-cent run legible instead of collapsing it to
 * zero, which is the reason the wide range was there in the first place.
 *
 * `null` and `undefined` mean the cost is unknown, not zero: no pricing is configured for the model
 * that produced it. They render as [UNKNOWN_COST], because a `0.00` in that case is a claim the
 * data does not support.
 *
 * The figures are estimates in the platform's single implicit currency, so no symbol is printed;
 * the surrounding text names the unit once rather than repeating it on every number.
 *
 * Usage in a template:
 *   {{ run.cost | agentosCost }}
 */
@Pipe({
  name: 'agentosCost',
  pure: true,
})
export class CostPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    if (value === null || value === undefined || !Number.isFinite(value)) return UNKNOWN_COST
    const safe = Math.max(0, value)
    if (safe === 0) return '0.00'
    return safe >= CENT ? safe.toFixed(2) : safe.toFixed(4)
  }
}
