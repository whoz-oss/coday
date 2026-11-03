import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Skill } from '@whoz-oss/agentos-api-client'
import { SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, switchMap } from 'rxjs'
import { SkillStateService } from '../../services/skill-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

/**
 * PlatformSkillsComponent — table view for platform-level skills.
 *
 * Loaded at /agentos/admin/skills. Create, update and delete at platform scope
 * require super-admin (the backend enforces it; the frontend shows the link only
 * when user.isAdmin).
 *
 * Platform skills are database-backed only: filesystem discovery is scoped to a
 * namespace configPath, so a `SKILL.md` never shows up here. A skill flagged
 * `readOnly` still only offers "View", as in the other skill lists.
 */
@Component({
  selector: 'agentos-platform-skills',
  imports: [SectionHeaderComponent, TableDirective, TagComponent, TableFilterComponent, RowDeleteActionComponent],
  templateUrl: './platform-skills.component.html',
  styleUrl: './platform-skills.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlatformSkillsComponent {
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly skillState = inject(SkillStateService)

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)

  /** Set when a deletion is refused, so the failure is not swallowed. */
  protected readonly deletion = createRowDeletionState()
  protected readonly skills = signal<Skill[]>([])
  protected readonly rowFilter = createRowFilter(this.skills, (skill) =>
    [skill.name, skill.description].filter(Boolean).join(' ')
  )

  constructor() {
    this.refresh$
      .pipe(
        switchMap(() => {
          this.isLoading.set(true)
          return this.skillState.listPlatform()
        }),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: (skills) => {
          this.skills.set(skills)
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected resourceCount(skill: Skill): number {
    return Object.keys(skill.resources ?? {}).length
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', 'admin', 'skills', 'new'])
  }

  protected inspect(skill: Skill): void {
    if (!skill.id) return
    void this.router.navigate(['/agentos', 'admin', 'skills', skill.id, 'inspect'])
  }

  protected edit(skill: Skill): void {
    if (!skill.id) return
    void this.router.navigate(['/agentos', 'admin', 'skills', skill.id, 'edit'])
  }

  protected confirmDelete(skill: Skill): void {
    if (!skill.id) return
    this.deletion.execute(skill.id, {
      request$: this.skillState.delete(skill.id).pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: (error) =>
        (error as { status?: number }).status === 403
          ? `Deleting "${skill.name}" requires platform administrator rights.`
          : `Deleting "${skill.name}" failed. Try again.`,
    })
  }
}
