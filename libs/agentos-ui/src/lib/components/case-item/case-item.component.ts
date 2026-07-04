import { ChangeDetectionStrategy, Component, input } from '@angular/core'
import { Case } from '@whoz-oss/agentos-api-client'
import { EntityListItem } from '@whoz-oss/design-system'

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
  readonly case = input.required<Case>()

  /** Map a Case to the EntityListItem shape expected by ds-entity-list. */
  static toListItem(c: Case): EntityListItem {
    return {
      id: c.id ?? '',
      // Cases don't have a user-facing name yet — display the full id
      name: c.id ?? '—',
      description: undefined,
    }
  }
}
