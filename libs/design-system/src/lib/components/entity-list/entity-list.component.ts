import {
  AfterViewInit,
  Component,
  computed,
  effect,
  ElementRef,
  input,
  OnDestroy,
  output,
  signal,
  TemplateRef,
  ViewChild,
  ChangeDetectionStrategy,
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
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './entity-list.component.scss',
})
export class EntityListComponent implements AfterViewInit, OnDestroy {
  readonly title = input.required<string>()
  readonly items = input<EntityListItem[]>([])
  readonly searchPlaceholder = input<string>('Filter…')
  readonly emptyMessage = input<string>('No items found.')
  readonly autoFocusSearch = input<boolean>(false)
  readonly cardMinWidth = input<string>('260px')
  readonly contentMaxWidth = input<string>('900px')
  readonly showCreate = input<boolean>(false)
  /** Hide the visible title (e.g. when a tab already labels the section); search/content stay. */
  readonly hideTitle = input<boolean>(false)
  readonly itemTemplate = input<TemplateRef<{ $implicit: EntityListItem }> | undefined>(undefined)
  /**
   * When > 0, enables infinite scroll: renders `pageSize` items initially and
   * loads more as the user scrolls to the bottom sentinel.
   * 0 (default) renders all items at once.
   */
  readonly pageSize = input<number>(0)
  /**
   * When true, the internal search filter is disabled — items are displayed as-is.
   * Use this when the parent manages its own filtering logic and passes pre-filtered items.
   * The search input remains visible; the parent receives the query via searchChanged.
   */
  readonly disableInternalFilter = input<boolean>(false)
  readonly toolbarOnly = input<boolean>(false)
  /**
   * When true, keep rendering the grouped (accordion) view while a search is active,
   * showing only the groups that still have matching items (a group with no match is
   * dropped, since [groupedItems] never creates an empty group). Default false keeps the
   * historical flat-while-searching behaviour for callers that don't opt in.
   */
  readonly keepGroupsWhileSearching = input<boolean>(false)

  readonly itemSelected = output<string>()
  readonly createRequested = output<void>()
  /**
   * Emitted on every keystroke and on clear.
   * Primarily useful when disableInternalFilter is true and the parent owns the filter logic.
   */
  readonly searchChanged = output<string>()

  @ViewChild('searchInput') private searchInputRef?: ElementRef<HTMLInputElement>
  @ViewChild('scrollSentinel') private sentinelRef?: ElementRef<HTMLElement>

  protected readonly searchQuery = signal('')
  protected readonly visibleCount = signal(0)
  private observer?: IntersectionObserver

  protected readonly isSearchActive = computed(() => this.searchQuery().trim().length > 0)

  protected readonly isPaged = computed(() => this.pageSize() > 0)

  protected readonly filteredItems = computed(() => {
    const all = this.items()
    if (this.disableInternalFilter()) return all
    const query = this.searchQuery().toLowerCase().trim()
    if (!query) return all
    return all.filter(
      (item) => item.name.toLowerCase().includes(query) || (item.description?.toLowerCase().includes(query) ?? false)
    )
  })

  protected readonly pagedItems = computed(() => {
    const all = this.filteredItems()
    return this.isPaged() ? all.slice(0, this.visibleCount()) : all
  })

  protected readonly hasMore = computed(() => this.isPaged() && this.visibleCount() < this.filteredItems().length)

  protected readonly groupedItems = computed<GroupedItems[]>(() => {
    const items = this.pagedItems()
    const groups: GroupedItems[] = []
    const byKey = new Map<string, GroupedItems>()

    // Preserve the order in which group keys first appear in [items], so the caller
    // controls group ordering purely through item order. Ungrouped items form a single
    // headerless group ('') positioned where the first ungrouped item appears — a named
    // group listed before it (e.g. "Favorites") therefore renders above the ungrouped rest.
    for (const item of items) {
      const key = item.groupKey ?? ''
      let group = byKey.get(key)
      if (!group) {
        group = { groupKey: key, groupLabel: key ? (item.groupLabel ?? key) : '', items: [] }
        byKey.set(key, group)
        groups.push(group)
      }
      group.items.push(item)
    }

    return groups
  })

  protected readonly hasGroups = computed(
    () =>
      (this.keepGroupsWhileSearching() || !this.isSearchActive()) && this.groupedItems().some((g) => g.groupKey !== '')
  )

  constructor() {
    // Reset visible count whenever the page size or search query changes
    effect(() => {
      const size = this.pageSize()
      this.searchQuery() // track
      this.visibleCount.set(size > 0 ? size : 0)
    })
  }

  ngAfterViewInit(): void {
    if (this.autoFocusSearch()) {
      setTimeout(() => this.searchInputRef?.nativeElement.focus(), 0)
    }
    if (this.isPaged() && this.sentinelRef) {
      this.observer = new IntersectionObserver(
        (entries) => {
          if (entries[0]?.isIntersecting && this.hasMore()) {
            this.visibleCount.update((n) => n + this.pageSize())
          }
        },
        { threshold: 0.1 }
      )
      this.observer.observe(this.sentinelRef.nativeElement)
    }
  }

  ngOnDestroy(): void {
    this.observer?.disconnect()
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
