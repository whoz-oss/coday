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
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { CaseItemComponent } from '../case-item/case-item.component'

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

  @ViewChild('caseItemTpl', { static: true }) caseItemTpl!: TemplateRef<{ $implicit: EntityListItem }>

  protected caseItems: EntityListItem[] = []

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['cases']) {
      this.caseItems = this.cases.map(CaseItemComponent.toListItem)
    }
  }

  protected onItemSelected(id: string): void {
    this.caseSelected.emit(id)
  }

  protected onCreateRequested(): void {
    this.createRequested.emit()
  }
}
