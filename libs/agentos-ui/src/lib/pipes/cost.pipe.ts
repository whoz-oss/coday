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

/** Marks a figure that is only a lower bound, because some of the usage behind it has no price. */
const AT_LEAST = '\u2265\u00a0'

/**
 * Formats an estimated cost for display.
 *
 * Two decimals is what the figures are read for — comparing runs, noticing an expensive one — and
 * the six that the `number: '1.2-6'` pipe used to print made every panel look like a measurement
 * instrument. Four decimals under a cent keep a sub-cent run legible instead of collapsing it to
 * zero, which is the reason the wide range was there in the first place.
 *
 * ## An unknown cost is not zero
 *
 * Two different shapes carry "unknown", and both have to be handled here or the panel states the
 * opposite of what it knows:
 *
 * - `null` / `undefined`, on a single record whose model has no pricing configured.
 * - a **zero** total paired with a non-zero [unknownCount]. The aggregates and `RunCostDto` type
 *   their cost as a plain number, so "nothing was priced" arrives as `0.0` with the count on the
 *   side. Printing `0.00` there — which is what the panels did, behind an `At least ` prefix —
 *   reads as a free run, and it is the common case: a namespace whose models carry no pricing
 *   produces it on every single run.
 *
 * A partial total is prefixed with `≥` instead: it is a lower bound, and the sign says so in one
 * character, in any sentence, without the caller having to get the capitalisation of a prose
 * prefix right.
 *
 * The figures are estimates in the platform's single implicit currency, so no symbol is printed;
 * the surrounding text names the unit once rather than repeating it on every number.
 *
 * Usage in a template:
 *   {{ run.cost | agentosCost: run.unknownCostCount }}
 */
@Pipe({
  name: 'agentosCost',
  pure: true,
})
export class CostPipe implements PipeTransform {
  /**
   * @param value the cost, or null when the single record it comes from has no price
   * @param unknownCount how much of the usage behind [value] has no price; 0 when all of it does
   */
  transform(value: number | null | undefined, unknownCount: number = 0): string {
    if (value === null || value === undefined || !Number.isFinite(value)) return UNKNOWN_COST
    const safe = Math.max(0, value)
    const partial = Number.isFinite(unknownCount) && unknownCount > 0
    // Nothing priced: there is no lower bound to report, only the absence of one.
    if (partial && safe === 0) return UNKNOWN_COST
    const formatted = safe === 0 ? '0.00' : safe >= CENT ? safe.toFixed(2) : safe.toFixed(4)
    return partial ? `${AT_LEAST}${formatted}` : formatted
  }
}
