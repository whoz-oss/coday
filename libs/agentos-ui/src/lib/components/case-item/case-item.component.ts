import { ChangeDetectionStrategy, Component, Input } from '@angular/core'
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
  standalone: true,
  template: '',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseItemComponent {
  @Input({ required: true }) case!: Case

  /** Map a Case to the EntityListItem shape expected by ds-entity-list. */
  static toListItem(c: Case): EntityListItem {
    return {
      id: c.id ?? '',
      name: c.title ?? c.id ?? '—',
      description: undefined,
    }
  }
}
