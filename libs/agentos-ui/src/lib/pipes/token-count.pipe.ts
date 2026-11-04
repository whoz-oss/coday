import { Pipe, PipeTransform } from '@angular/core'

/**
 * Tokens in the compact form a status line is read in: `845`, `1.2k`, `12k`, `1.2m`.
 *
 * Compact rather than grouped-exact (`12,345`) wherever the figure is scanned instead of audited:
 * the question it answers is an order of magnitude, and three extra characters per number is what
 * makes a summary line wrap. The exact count stays where it is read deliberately — the usage table
 * and the panel body keep `| number`.
 *
 * One decimal below ten thousand, none above: `1.2k` distinguishes two short runs, while the tenth
 * of a thousand in `12.3k` is noise next to the twelve.
 */
export function formatTokenCount(tokens: number | null | undefined): string {
  if (tokens === null || tokens === undefined || !Number.isFinite(tokens)) return '0'
  const safe = Math.max(0, tokens)
  if (safe < 1000) return String(Math.round(safe))
  // Each tier is chosen from the value as it will be rounded, not as it arrives. Testing the raw
  // value instead puts 9 999 and 10 000 in different tiers though both read ten thousand, and lets
  // 999 999 print `1000k` — four digits and a unit, beside the `1.0m` of its own neighbour.
  const thousands = safe / 1000
  const rounded = Number(thousands.toFixed(1))
  if (rounded >= 1000) return `${(safe / 1_000_000).toFixed(1)}m`
  return rounded >= 10 ? `${Math.round(thousands)}k` : `${rounded.toFixed(1)}k`
}

/**
 * Template access to [formatTokenCount].
 *
 * Usage:
 *   {{ aggregate.totalTokens | agentosTokens }}
 */
@Pipe({
  name: 'agentosTokens',
  pure: true,
})
export class TokenCountPipe implements PipeTransform {
  transform(value: number | null | undefined): string {
    return formatTokenCount(value)
  }
}
