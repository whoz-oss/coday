import {
  ChangeDetectionStrategy,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  TemplateRef,
  ViewChild,
} from '@angular/core'
import { Case } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, IconButtonComponent } from '@whoz-oss/design-system'
import { CaseItemComponent, CaseListItem } from '../case-item/case-item.component'

/**
 * CaseDrawerComponent — presentational drawer content for the case list.
 *
 * Receives cases as @Input, maps them to EntityListItem[], and renders
 * ds-entity-list with a minimal itemTemplate. Navigation is delegated
 * upward via (caseSelected).
 *
 * Kept presentational: no Router, no HTTP, no state services.
 */
@Component({
  selector: 'agentos-case-drawer',
  standalone: true,
  imports: [EntityListComponent, IconButtonComponent],
  templateUrl: './case-drawer.component.html',
  styleUrl: './case-drawer.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CaseDrawerComponent implements OnChanges {
  @Input() cases: Case[] = []
  @Input() activeCaseId: string | null = null

  @Output() caseSelected = new EventEmitter<string>()
  @Output() createRequested = new EventEmitter<void>()
  @Output() closeRequested = new EventEmitter<void>()
  @Output() deleteRequested = new EventEmitter<string>()
  @Output() starToggled = new EventEmitter<{ id: string; starred: boolean }>()

  @ViewChild('caseItemTpl', { static: true }) caseItemTpl!: TemplateRef<{ $implicit: CaseListItem }>

  protected caseItems: CaseListItem[] = []

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['cases']) {
      const sorted = [...this.cases].sort((a, b) => Number(b.favorite ?? false) - Number(a.favorite ?? false))
      const hasFavorites = sorted.some((c) => c.favorite)
      this.caseItems = sorted.map((c) => {
        const item = CaseItemComponent.toListItem(c)
        if (hasFavorites) {
          item.groupKey = c.favorite ? 'favorites' : 'cases'
          item.groupLabel = c.favorite ? 'Favorites' : 'Others'
        }
        return item
      })
    }
  }

  protected onItemSelected(id: string): void {
    this.caseSelected.emit(id)
  }

  protected onCreateRequested(): void {
    this.createRequested.emit()
  }

  protected onDeleteRequested(id: string): void {
    this.deleteRequested.emit(id)
  }

  protected onStarToggled(item: CaseListItem): void {
    // Optimistically flip locally so a rapid second click toggles from the new state
    // (not the stale one) and the star icon updates immediately; the refresh reconciles.
    item.favorite = !item.favorite
    this.starToggled.emit({ id: item.id, starred: item.favorite })
  }
}
