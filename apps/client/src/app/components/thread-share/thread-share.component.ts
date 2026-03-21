import { Component, EventEmitter, Input, Output, signal } from '@angular/core'
import { MatIconModule } from '@angular/material/icon'
import { MatButtonModule } from '@angular/material/button'
import { MatTooltipModule } from '@angular/material/tooltip'
import { UserAutocompleteComponent } from '../user-autocomplete/user-autocomplete.component'

/**
 * ThreadShareComponent - Manages thread participant sharing
 *
 * Displays current thread participants and allows the owner to add/remove users.
 * Non-owners see the participant list in read-only mode.
 *
 * The parent is responsible for driving isAdding state by calling setAdding()
 * after emitting userAdded, so the autocomplete input is properly disabled during
 * the HTTP request.
 */
@Component({
  selector: 'app-thread-share',
  standalone: true,
  imports: [MatIconModule, MatButtonModule, MatTooltipModule, UserAutocompleteComponent],
  templateUrl: './thread-share.component.html',
  styleUrl: './thread-share.component.scss',
})
export class ThreadShareComponent {
  @Input({ required: true }) threadId!: string
  @Input({ required: true }) users: { userId: string }[] = []
  @Input({ required: true }) ownerUsername!: string
  @Input({ required: true }) currentUsername!: string

  @Output() userAdded = new EventEmitter<string>()
  @Output() userRemoved = new EventEmitter<string>()

  protected readonly isAdding = signal(false)
  protected readonly errorMessage = signal('')

  get isOwner(): boolean {
    return this.currentUsername === this.ownerUsername
  }

  get excludedUserIds(): string[] {
    return [this.currentUsername, ...this.users.map((u) => u.userId)]
  }

  /** Called by parent to drive loading state during async add operation */
  setAdding(value: boolean): void {
    this.isAdding.set(value)
  }

  /** Called by parent to surface a server-side error */
  setError(message: string): void {
    this.errorMessage.set(message)
    this.isAdding.set(false)
  }

  onUserSelected(userId: string): void {
    this.errorMessage.set('')
    this.userAdded.emit(userId)
    // isAdding will be set to true by parent via setAdding(true)
  }

  removeUser(userId: string): void {
    this.userRemoved.emit(userId)
  }
}
