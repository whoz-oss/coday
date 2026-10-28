import { Signal, computed, signal } from '@angular/core'

/** Local, reactive table filtering without mutating the source rows. */
export function createRowFilter<T>(rows: Signal<readonly T[]>, searchableText: (row: T) => string) {
  const query = signal('')
  const filteredRows = computed(() => {
    const normalizedQuery = query().trim().toLocaleLowerCase()
    if (!normalizedQuery) return rows()
    return rows().filter((row) => searchableText(row).toLocaleLowerCase().includes(normalizedQuery))
  })

  return { query, filteredRows }
}
