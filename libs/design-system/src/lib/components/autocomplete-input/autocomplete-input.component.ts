import { Component, DestroyRef, ElementRef, EventEmitter, inject, Input, OnInit, Output } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Subject, debounceTime, distinctUntilChanged, switchMap, of } from 'rxjs'
import { AutocompleteDataSource, AutocompleteItem } from './autocomplete-data-source'

/**
 * AutocompleteInputComponent — a generic, self-contained autocomplete text input.
 *
 * Accepts a pluggable `AutocompleteDataSource` to decouple search logic from the UI.
 * Debounces keystrokes and cancels stale in-flight searches via switchMap.
 * Emits `itemSelected` when the user picks a suggestion; clears its own state afterwards.
 *
 * CSS contract: relies on --color-border, --color-primary, --color-bg-surface,
 * --color-bg-hover, --color-text, --color-text-secondary from the host theme.
 *
 * @example
 * <ds-autocomplete-input
 *   [dataSource]="userSource"
 *   placeholder="Add a user…"
 *   (itemSelected)="onUserSelected($event)"
 * />
 */
@Component({
  selector: 'ds-autocomplete-input',
  standalone: true,
  imports: [],
  templateUrl: './autocomplete-input.component.html',
  styleUrl: './autocomplete-input.component.scss',
})
export class AutocompleteInputComponent implements OnInit {
  @Input({ required: true }) dataSource!: AutocompleteDataSource
  @Input() placeholder: string = ''
  @Input() disabled: boolean = false

  @Output() itemSelected = new EventEmitter<AutocompleteItem>()

  private readonly elementRef = inject(ElementRef)
  private readonly destroyRef = inject(DestroyRef)
  private readonly query$ = new Subject<string>()

  protected query: string = ''
  protected items: AutocompleteItem[] = []
  protected showDropdown: boolean = false
  protected selectedIndex: number = -1

  ngOnInit(): void {
    this.query$
      .pipe(
        debounceTime(200),
        distinctUntilChanged(),
        switchMap((q) => {
          const trimmed = q.trim()
          if (!trimmed) {
            return of([] as AutocompleteItem[])
          }
          return this.dataSource.search(trimmed)
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe((results) => {
        this.items = results
        this.selectedIndex = -1
        this.showDropdown = results.length > 0
      })
  }

  protected onInput(event: Event): void {
    this.query = (event.target as HTMLInputElement).value
    this.query$.next(this.query)
  }

  protected onFocus(): void {
    if (this.items.length > 0) {
      this.showDropdown = true
    }
  }

  protected onBlur(): void {
    // Delay to allow mousedown/touchstart on dropdown items to fire first
    setTimeout(() => this.closeDropdown(), 150)
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (!this.showDropdown) return

    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault()
        this.selectedIndex = Math.min(this.selectedIndex + 1, this.items.length - 1)
        this.scrollSelectedIntoView()
        break

      case 'ArrowUp':
        event.preventDefault()
        this.selectedIndex = Math.max(this.selectedIndex - 1, 0)
        this.scrollSelectedIntoView()
        break

      case 'Enter':
        event.preventDefault()
        if (this.selectedIndex >= 0 && this.selectedIndex < this.items.length) {
          this.selectItem(this.items[this.selectedIndex]!)
        }
        break

      case 'Escape':
        event.preventDefault()
        this.closeDropdown()
        break
    }
  }

  protected selectItem(item: AutocompleteItem): void {
    this.itemSelected.emit(item)
    this.query = ''
    this.closeDropdown()
  }

  private closeDropdown(): void {
    this.showDropdown = false
    this.selectedIndex = -1
    this.items = []
  }

  private scrollSelectedIntoView(): void {
    // Defer to next tick so the DOM reflects the updated selectedIndex binding
    setTimeout(() => {
      const host = this.elementRef.nativeElement as HTMLElement
      const dropdown = host.querySelector('.autocomplete-dropdown')
      const selected = dropdown?.querySelector('.autocomplete-option.selected')
      selected?.scrollIntoView({ block: 'nearest' })
    })
  }
}
