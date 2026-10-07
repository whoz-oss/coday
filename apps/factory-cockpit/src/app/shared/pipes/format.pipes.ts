import { Pipe, PipeTransform } from '@angular/core'

/**
 * 1.0723 → "$1.0723" (format des captures, indépendant de la locale).
 *
 * When the value is a lower bound (`unknownCostCount > 0`) the output is
 * prefixed with `≥` so an incomplete cost is never displayed as exact.
 */
@Pipe({ name: 'usd' })
export class UsdPipe implements PipeTransform {
  transform(value: number | null | undefined, digits = 4, unknownCostCount = 0): string {
    if (value == null) return '—'
    const formatted = `$${value.toFixed(digits)}`
    return unknownCostCount > 0 ? `≥ ${formatted}` : formatted
  }
}

/** 732 → "12m 12s" */
@Pipe({ name: 'duration' })
export class DurationPipe implements PipeTransform {
  transform(sec: number | null | undefined): string {
    if (sec == null) return '—'
    if (sec < 60) return `${sec.toFixed(2)}s`
    const m = Math.floor(sec / 60)
    const s = Math.round(sec % 60)
    return `${m}m ${s.toString().padStart(2, '0')}s`
  }
}

/** 606500 → "606.5k" */
@Pipe({ name: 'tokens' })
export class TokensPipe implements PipeTransform {
  transform(n: number | null | undefined): string {
    if (n == null) return '—'
    if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
    if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
    return `${n}`
  }
}
