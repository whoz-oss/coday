import { ChangeDetectionStrategy, Component, Input } from '@angular/core'
import { Case, CaseRoleEnum } from '@whoz-oss/agentos-api-client'
import { EntityListItem } from '@whoz-oss/design-system'

/**
 * A drawer list item for a case. Extends the shared [EntityListItem] with the
 * case-specific `canDelete` affordance flag (kept off the design-system interface).
 */
export interface CaseListItem extends EntityListItem {
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
  standalone: true,
  template: '',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseItemComponent {
  @Input({ required: true }) case!: Case

  /** Map a Case to the list-item shape expected by ds-entity-list. */
  static toListItem(c: Case): CaseListItem {
    return {
      id: c.id ?? '',
      // Cases don't have a user-facing name yet — display the full id
      name: c.id ?? '—',
      description: undefined,
      favorite: c.favorite,
      // Only a direct ADMIN can delete; MEMBER (or no direct relation) cannot.
      canDelete: c.role === CaseRoleEnum.ADMIN,
    }
  }
}
