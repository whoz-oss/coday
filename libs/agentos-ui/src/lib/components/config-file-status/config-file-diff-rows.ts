import type { Change } from 'diff'

/** One line of the comparison between a file and its database copy. */
export interface ConfigFileDiffRow {
  /** `removed`: only in the file. `added`: only in the copy. `skipped`: a run of unchanged lines left out. */
  readonly kind: 'same' | 'removed' | 'added' | 'skipped'
  readonly text: string
}

/** Unchanged lines kept around each change; longer unchanged runs fold into one `skipped` row. */
const CONTEXT = 2

/**
 * The rows that show how [changes] (a line diff from the file to the copy, see `diffLines` of the `diff`
 * package) turn the file into the copy: every changed line, with [CONTEXT] unchanged lines around.
 */
export function configFileDiffRows(changes: readonly Change[]): ConfigFileDiffRow[] {
  const lines = changes.flatMap((change) =>
    splitLines(change.value).map((text) => ({
      kind: change.added ? ('added' as const) : change.removed ? ('removed' as const) : ('same' as const),
      text,
    }))
  )
  const near = lines.map((_, index) =>
    lines.slice(Math.max(0, index - CONTEXT), index + CONTEXT + 1).some((line) => line.kind !== 'same')
  )
  const rows: ConfigFileDiffRow[] = []
  let skipped = 0
  lines.forEach((line, index) => {
    if (near[index]) {
      if (skipped > 0) rows.push(skippedRow(skipped))
      skipped = 0
      rows.push(line)
    } else {
      skipped++
    }
  })
  if (skipped > 0) rows.push(skippedRow(skipped))
  return rows
}

function skippedRow(count: number): ConfigFileDiffRow {
  return { kind: 'skipped', text: `${count} unchanged line${count > 1 ? 's' : ''}` }
}

/** The lines of [value], without the empty one a trailing newline would add. */
function splitLines(value: string): string[] {
  const lines = value.split('\n')
  return lines[lines.length - 1] === '' ? lines.slice(0, -1) : lines
}
