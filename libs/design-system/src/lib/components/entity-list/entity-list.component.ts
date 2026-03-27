import {
  AfterViewInit,
  Component,
  computed,
  ElementRef,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  signal,
  SimpleChanges,
  TemplateRef,
  ViewChild,
} from '@angular/core'
import { NgTemplateOutlet } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { MatIconModule } from '@angular/material/icon'
import { MatIconButton } from '@angular/material/button'
import { MatExpansionModule } from '@angular/material/expansion'
import { MatFormFieldModule } from '@angular/material/form-field'
import { MatInputModule } from '@angular/material/input'
import { EntityCardComponent, EntityCardBadge } from '../entity-card/entity-card.component'

export interface EntityListItem {
  id: string
  name: string
  description?: string
  badges?: EntityCardBadge[]
  groupKey?: string
  groupLabel?: string
}

export interface GroupedItems {
  groupKey: string
  groupLabel: string
  items: EntityListItem[]
}

/**
 * DsEntityList — a full-page generic list.
 *
 * `items` is bridged via `ngOnChanges` into an internal signal so that
 * `computed` derivations (filtering, grouping) re-evaluate reactively
 * whenever the parent updates the binding.
 */
@Component({
  selector: 'ds-entity-list',
  standalone: true,
  imports: [
    FormsModule,
    MatIconModule,
    MatIconButton,
    MatExpansionModule,
    MatFormFieldModule,
    MatInputModule,
    EntityCardComponent,
    NgTemplateOutlet,
  ],
  templateUrl: './entity-list.component.html',
  styleUrl: './entity-list.component.scss',
})
export class EntityListComponent implements AfterViewInit, OnChanges {
  @Input({ required: true }) title!: string
  @Input() items: EntityListItem[] = []
  @Input() searchPlaceholder: string = 'Filter…'
  @Input() emptyMessage: string = 'No items found.'
  @Input() autoFocusSearch: boolean = false
  @Input() cardMinWidth: string = '260px'
  @Input() contentMaxWidth: string = '900px'
  @Input() showCreate: boolean = false
  @Input() itemTemplate?: TemplateRef<{ $implicit: EntityListItem }>

  @Output() itemSelected = new EventEmitter<string>()
  @Output() createRequested = new EventEmitter<void>()

  @ViewChild('searchInput') private searchInputRef?: ElementRef<HTMLInputElement>

  /** Internal signal bridging the `items` @Input — makes computed() reactive. */
  private readonly itemsSignal = signal<EntityListItem[]>([])

  protected readonly searchQuery = signal('')

  protected readonly isSearchActive = computed(() => this.searchQuery().trim().length > 0)

  protected readonly filteredItems = computed(() => {
    const query = this.searchQuery().toLowerCase().trim()
    const all = this.itemsSignal()
    if (!query) return all
    return all.filter(
      (item) => item.name.toLowerCase().includes(query) || (item.description?.toLowerCase().includes(query) ?? false)
    )
  })

  protected readonly groupedItems = computed<GroupedItems[]>(() => {
    const items = this.filteredItems()
    const ungrouped: EntityListItem[] = []
    const groupMap = new Map<string, GroupedItems>()

    for (const item of items) {
      if (!item.groupKey) {
        ungrouped.push(item)
        continue
      }
      if (!groupMap.has(item.groupKey)) {
        groupMap.set(item.groupKey, {
          groupKey: item.groupKey,
          groupLabel: item.groupLabel ?? item.groupKey,
          items: [],
        })
      }
      groupMap.get(item.groupKey)!.items.push(item)
    }

    const groups: GroupedItems[] = []
    if (ungrouped.length > 0) {
      groups.push({ groupKey: '', groupLabel: '', items: ungrouped })
    }
    groups.push(...groupMap.values())
    return groups
  })

  protected readonly hasGroups = computed(
    () => !this.isSearchActive() && this.groupedItems().some((g) => g.groupKey !== '')
  )

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['items']) {
      this.itemsSignal.set(this.items)
    }
  }

  ngAfterViewInit(): void {
    if (this.autoFocusSearch) {
      setTimeout(() => this.searchInputRef?.nativeElement.focus(), 0)
    }
  }

  protected onSearchChange(value: string): void {
    this.searchQuery.set(value)
  }

  protected clearSearch(): void {
    this.searchQuery.set('')
    this.searchInputRef?.nativeElement.focus()
  }

  protected onItemSelected(id: string): void {
    this.itemSelected.emit(id)
  }

  protected onCreateRequested(): void {
    this.createRequested.emit()
  }
}
