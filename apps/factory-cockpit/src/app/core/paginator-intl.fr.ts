import { Injectable } from '@angular/core'
import { MatPaginatorIntl } from '@angular/material/paginator'

/**
 * English labels for `mat-paginator`.
 *
 * Angular Material ships English paginator strings by default, but we
 * override them explicitly to ensure consistency across locale changes.
 */
@Injectable()
export class FrPaginatorIntl extends MatPaginatorIntl {
  override itemsPerPageLabel = 'Items per page'
  override nextPageLabel = 'Next page'
  override previousPageLabel = 'Previous page'
  override firstPageLabel = 'First page'
  override lastPageLabel = 'Last page'

  override getRangeLabel = (page: number, pageSize: number, length: number): string => {
    if (length === 0 || pageSize === 0) {
      return `0 of ${length}`
    }
    const startIndex = page * pageSize
    const endIndex = Math.min(startIndex + pageSize, length)
    return `${startIndex + 1} – ${endIndex} of ${length}`
  }
}
