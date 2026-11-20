export type DiffLineKind = 'added' | 'removed' | 'context'
export interface DiffLine {
  kind: DiffLineKind
  text: string
}
/** Unchanged lines left out of the diff. */
export interface DiffGap {
  kind: 'gap'
  count: number
}
export type DiffRow = DiffLine | DiffGap

/** Above this many line pairs, the LCS table is not built: the whole value is shown as replaced. */
const MAX_LCS_CELLS = 1_000_000

/**
 * A line diff of two texts. `null` stands for an absent text: everything is added (creation) or
 * removed (deletion). Unchanged lines further than `context` lines from a change are collapsed
 * into a gap, but only when that hides at least 2 lines.
 */
export function diffLines(before: string | null, after: string | null, context = 3): DiffRow[] {
  if (before === null && after === null) return []
  if (before === null) return lines(after as string).map((text) => ({ kind: 'added', text }))
  if (after === null) return lines(before).map((text) => ({ kind: 'removed', text }))
  return collapse(diff(lines(before), lines(after)), context)
}

function lines(text: string): string[] {
  return text.split('\n')
}

function diff(a: string[], b: string[]): DiffLine[] {
  const n = a.length
  const m = b.length
  if (n * m > MAX_LCS_CELLS) {
    return [
      ...a.map((text): DiffLine => ({ kind: 'removed', text })),
      ...b.map((text): DiffLine => ({ kind: 'added', text })),
    ]
  }

  // lcs[i][j]: length of the longest common subsequence of a[i..] and b[j..], flattened.
  const width = m + 1
  const lcs = new Uint32Array((n + 1) * width)
  const at = (i: number, j: number): number => lcs[i * width + j] ?? 0
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i * width + j] = a[i] === b[j] ? at(i + 1, j + 1) + 1 : Math.max(at(i + 1, j), at(i, j + 1))
    }
  }

  const result: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < n || j < m) {
    const lineA = a[i]
    const lineB = b[j]
    if (lineA !== undefined && lineA === lineB) {
      result.push({ kind: 'context', text: lineA })
      i++
      j++
    } else if (lineA !== undefined && (lineB === undefined || at(i + 1, j) >= at(i, j + 1))) {
      result.push({ kind: 'removed', text: lineA })
      i++
    } else if (lineB !== undefined) {
      result.push({ kind: 'added', text: lineB })
      j++
    }
  }
  return removalsFirst(result)
}

/** Inside a run of changed lines, the removals come before the additions. */
function removalsFirst(rows: DiffLine[]): DiffLine[] {
  const result: DiffLine[] = []
  let removed: DiffLine[] = []
  let added: DiffLine[] = []
  const flush = () => {
    result.push(...removed, ...added)
    removed = []
    added = []
  }
  for (const row of rows) {
    if (row.kind === 'context') {
      flush()
      result.push(row)
    } else if (row.kind === 'removed') {
      removed.push(row)
    } else {
      added.push(row)
    }
  }
  flush()
  return result
}

function collapse(rows: DiffLine[], context: number): DiffRow[] {
  const keep = rows.map((row) => row.kind !== 'context')
  rows.forEach((row, index) => {
    if (row.kind === 'context') return
    for (let k = Math.max(0, index - context); k <= Math.min(rows.length - 1, index + context); k++) keep[k] = true
  })

  const result: DiffRow[] = []
  let index = 0
  while (index < rows.length) {
    const row = rows[index]
    if (row && keep[index]) {
      result.push(row)
      index++
      continue
    }
    let end = index
    while (end < rows.length && !keep[end]) end++
    if (end - index >= 2) result.push({ kind: 'gap', count: end - index })
    else result.push(...rows.slice(index, end))
    index = end
  }
  return result
}
