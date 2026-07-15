import { ChangeDetectionStrategy, Component, Input } from '@angular/core'
import { Case, CaseRoleEnum } from '@whoz-oss/agentos-api-client'
import { EntityListItem } from '@whoz-oss/design-system'

/**
 * A drawer list item for a case. Extends the shared [EntityListItem] with the
 * case-specific `favorite` and `canDelete` flags (kept off the design-system interface,
 * which only needs `groupKey`/`groupLabel`).
 */
export interface CaseListItem extends EntityListItem {
  /** Per-user favorite flag — drives the Favorites grouping and the star menu action. */
  favorite: boolean
  /** Whether the caller may delete this case (direct ADMIN relation). Gates the delete button. */
  canDelete: boolean
}

/**
 * CaseItemComponent — maps a Case to an EntityListItem for use in ds-entity-list.
 *
 * Presentational helper: pure mapping, no state, no side effects.
 * Used as itemTemplate inside CaseDrawerComponent.
 */
@Component({
  selector: 'agentos-case-item',
  template: '',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseItemComponent {
  @Input({ required: true }) case!: Case

  /** Map a Case to the list-item shape expected by ds-entity-list. */
  static toListItem(c: Case): CaseListItem {
    return {
      id: c.id ?? '',
      name: c.title ?? c.id ?? '—',
      description: undefined,
      favorite: c.favorite,
      // Only a direct ADMIN can delete; MEMBER (or no direct relation) cannot.
      canDelete: c.role === CaseRoleEnum.ADMIN,
    }
  }
}
