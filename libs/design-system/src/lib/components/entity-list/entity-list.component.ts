import {
  AfterViewInit,
  Component,
  computed,
  ElementRef,
  input,
  output,
  signal,
  TemplateRef,
  ViewChild,
} from '@angular/core'
import { NgTemplateOutlet } from '@angular/common'
import { MatExpansionModule } from '@angular/material/expansion'
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
 * Uses signal-based inputs throughout. `computed` derivations (filtering,
 * grouping) re-evaluate reactively whenever `items` or the search query change.
 */
@Component({
  selector: 'ds-entity-list',
  host: {
    '[attr.title]': 'null',
    '[class.ds-entity-list--toolbar-only]': 'toolbarOnly()',
  },
  imports: [MatExpansionModule, EntityCardComponent, NgTemplateOutlet],
  templateUrl: './entity-list.component.html',
  styleUrl: './entity-list.component.scss',
})
export class EntityListComponent implements AfterViewInit {
  readonly title = input.required<string>()
  readonly items = input<EntityListItem[]>([])
  readonly searchPlaceholder = input<string>('Filter…')
  readonly emptyMessage = input<string>('No items found.')
  readonly autoFocusSearch = input<boolean>(false)
  readonly cardMinWidth = input<string>('260px')
  readonly contentMaxWidth = input<string>('900px')
  readonly showCreate = input<boolean>(false)
  readonly itemTemplate = input<TemplateRef<{ $implicit: EntityListItem }> | undefined>(undefined)
  /**
   * When true, the internal search filter is disabled — items are displayed as-is.
   * Use this when the parent manages its own filtering logic and passes pre-filtered items.
   * The search input remains visible; the parent receives the query via searchChanged.
   */
  readonly disableInternalFilter = input<boolean>(false)
  readonly toolbarOnly = input<boolean>(false)

  readonly itemSelected = output<string>()
  readonly createRequested = output<void>()
  /**
   * Emitted on every keystroke and on clear.
   * Primarily useful when disableInternalFilter is true and the parent owns the filter logic.
   */
  readonly searchChanged = output<string>()

  @ViewChild('searchInput') private searchInputRef?: ElementRef<HTMLInputElement>

  protected readonly searchQuery = signal('')

  protected readonly isSearchActive = computed(() => this.searchQuery().trim().length > 0)

  protected readonly filteredItems = computed(() => {
    const all = this.items()
    if (this.disableInternalFilter()) return all
    const query = this.searchQuery().toLowerCase().trim()
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

  ngAfterViewInit(): void {
    if (this.autoFocusSearch()) {
      setTimeout(() => this.searchInputRef?.nativeElement.focus(), 0)
    }
  }

  protected onSearchChange(value: string): void {
    this.searchQuery.set(value)
    this.searchChanged.emit(value)
  }

  protected clearSearch(): void {
    this.searchQuery.set('')
    this.searchChanged.emit('')
    this.searchInputRef?.nativeElement.focus()
  }

  protected onItemSelected(id: string): void {
    this.itemSelected.emit(id)
  }

  protected onCreateRequested(): void {
    this.createRequested.emit()
  }
}
