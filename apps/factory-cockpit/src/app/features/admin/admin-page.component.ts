import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core'
import { FormsModule } from '@angular/forms'
import { MatButtonModule } from '@angular/material/button'
import { MatIconModule } from '@angular/material/icon'
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner'
import { FactoryApiError } from '../../core/factory-api.service'
import { RunConcurrencyStateService } from '../../core/run-concurrency-state.service'
import { ShellState } from '../../core/shell-state'

/**
 * Factory administration: the maximum number of workflow runs executed in parallel.
 *
 * The value is global to the Factory instance and applies at the next drain tick, with no restart.
 * Extra runs wait in the queue; 1 restores serialized execution.
 */
@Component({
  selector: 'sf-admin-page',
  imports: [FormsModule, MatButtonModule, MatIconModule, MatProgressSpinnerModule],
  templateUrl: './admin-page.component.html',
  styleUrl: './admin-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [RunConcurrencyStateService],
})
export class AdminPageComponent {
  private readonly state = inject(RunConcurrencyStateService)

  protected readonly settings = this.state.settings
  protected readonly loading = this.state.loading
  protected readonly saving = this.state.saving

  /** What the user typed; `undefined` while untouched, `null` when the field is empty or not numeric. */
  private readonly edited = signal<number | null | undefined>(undefined)

  /** The value shown in the input: the user's edit, otherwise the server value. */
  protected readonly draft = computed(() => {
    const edited = this.edited()
    return edited !== undefined ? edited : (this.settings()?.maxParallelRuns ?? null)
  })

  protected readonly savedMessage = signal<string | null>(null)
  protected readonly saveError = signal<string | null>(null)

  protected readonly loadError = computed(() =>
    this.state.error() && !this.settings()
      ? this.state.error()?.message?.trim() || 'Unable to load the run concurrency setting.'
      : null
  )

  /** Inline validation message of the edited value, or null when there is nothing to reject. */
  protected readonly validation = computed(() => {
    const current = this.settings()
    if (!current || this.edited() === undefined) return null
    const value = this.draft()
    if (value === null || !Number.isInteger(value)) return 'Enter a whole number.'
    if (value < current.min || value > current.max) return `Enter a value between ${current.min} and ${current.max}.`
    return null
  })

  protected readonly canSave = computed(() => {
    const current = this.settings()
    const value = this.draft()
    return !!current && !this.validation() && value !== null && value !== current.maxParallelRuns && !this.saving()
  })

  constructor() {
    inject(ShellState).crumbs.set([{ label: 'Admin' }])
    this.load()
  }

  protected load(): void {
    this.edited.set(undefined)
    this.savedMessage.set(null)
    this.saveError.set(null)
    this.state.load()
  }

  protected onValueChange(value: number | string | null): void {
    this.savedMessage.set(null)
    this.saveError.set(null)
    this.edited.set(value === null || value === '' ? null : Number(value))
  }

  protected save(): void {
    const value = this.draft()
    if (!this.canSave() || value === null) return
    this.savedMessage.set(null)
    this.saveError.set(null)
    this.state.save(value).subscribe({
      next: () => {
        this.edited.set(undefined)
        this.savedMessage.set(`Saved: up to ${this.settings()?.maxParallelRuns ?? value} workflow runs in parallel.`)
      },
      error: (error: FactoryApiError) => {
        const message = error.message?.trim() || 'Unable to save the setting.'
        this.saveError.set(`${message} (${error.code})`)
      },
    })
  }

  protected formatUpdatedAt(value: string): string {
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
  }
}
