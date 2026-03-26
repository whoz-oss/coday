import { Component, EventEmitter, inject, Input, Output } from '@angular/core'
import { Observable, map } from 'rxjs'
import { AutocompleteDataSource, AutocompleteInputComponent, AutocompleteItem } from '@whoz-oss/design-system'
import { UserApiService } from '../../core/services/user-api.service'

/**
 * UserAutocompleteComponent — user search autocomplete.
 *
 * Implements AutocompleteDataSource directly so the component IS the data source.
 * Consumers provide an exclusion list and react to user selection.
 *
 * @example
 * <app-user-autocomplete
 *   [excludedUserIds]="excludedIds"
 *   (userSelected)="onUserSelected($event)"
 * />
 */
@Component({
  selector: 'app-user-autocomplete',
  standalone: true,
  imports: [AutocompleteInputComponent],
  styles: [':host { display: block; }'],
  template: `
    <ds-autocomplete-input
      [dataSource]="this"
      [placeholder]="placeholder"
      [disabled]="disabled"
      (itemSelected)="onItemSelected($event)"
    />
  `,
})
export class UserAutocompleteComponent implements AutocompleteDataSource {
  @Input() excludedUserIds: string[] = []
  @Input() placeholder: string = 'Search username'
  @Input() disabled: boolean = false

  @Output() userSelected = new EventEmitter<string>()

  private readonly userApi = inject(UserApiService)

  search(query: string): Observable<AutocompleteItem[]> {
    const lowerQuery = query.toLowerCase()
    return this.userApi.listUsers().pipe(
      map((users) =>
        users
          .filter((u) => !this.excludedUserIds.includes(u.username))
          .filter((u) => u.username.toLowerCase().includes(lowerQuery))
          .map((u) => ({ id: u.username, name: u.username }))
      )
    )
  }

  protected onItemSelected(item: AutocompleteItem): void {
    this.userSelected.emit(item.id)
  }
}
