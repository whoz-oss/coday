import { Injectable } from '@angular/core'
import { MatPaginatorIntl } from '@angular/material/paginator'

/**
 * French labels for `mat-paginator`.
 *
 * The application runs with `LOCALE_ID` set to `fr` but Angular Material ships
 * English paginator strings by default, so we override them explicitly.
 */
@Injectable()
export class FrPaginatorIntl extends MatPaginatorIntl {
  override itemsPerPageLabel = 'Éléments par page'
  override nextPageLabel = 'Page suivante'
  override previousPageLabel = 'Page précédente'
  override firstPageLabel = 'Première page'
  override lastPageLabel = 'Dernière page'

  override getRangeLabel = (page: number, pageSize: number, length: number): string => {
    if (length === 0 || pageSize === 0) {
      return `0 sur ${length}`
    }
    const startIndex = page * pageSize
    const endIndex = Math.min(startIndex + pageSize, length)
    return `${startIndex + 1} – ${endIndex} sur ${length}`
  }
}
