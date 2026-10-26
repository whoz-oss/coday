import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { Router } from '@angular/router'
import { Skill } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem, IconButtonComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, map, shareReplay, switchMap } from 'rxjs'
import { SkillStateService } from '../../services/skill-state.service'
import { SkillItemComponent } from '../skill-item/skill-item.component'

/**
 * PlatformSkillsComponent — list view for platform-level skills.
 *
 * Loaded at /agentos/admin/skills. Create, update and delete at platform scope
 * require super-admin (the backend enforces it; the frontend shows the link only
 * when user.isAdmin).
 *
 * Platform skills are database-backed only: filesystem discovery is scoped to a
 * namespace configPath, so a `SKILL.md` never shows up here.
 */
@Component({
  selector: 'agentos-platform-skills',
  imports: [AsyncPipe, EntityListComponent, SkillItemComponent, IconButtonComponent],
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
  protected readonly errorMessage = signal<string | null>(null)

  /** Raw platform skills, kept for delete lookups. */
  private readonly skills$ = this.refresh$.pipe(
    switchMap(() => {
      this.isLoading.set(true)
      return this.skillState.listPlatform()
    }),
    // Subscribed twice — by the async pipe and by the constructor, which keeps the full
    // objects for the item template — so without this the list is fetched twice per refresh.
    shareReplay({ bufferSize: 1, refCount: true })
  )

  /** Mapped to EntityListItem[] for ds-entity-list. */
  protected readonly skillItems$ = this.skills$.pipe(
    map((skills) =>
      skills.map(
        (s: Skill): EntityListItem => ({
          id: s.id ?? '',
          name: s.name,
          description: s.description,
        })
      )
    )
  )

  /** Full skill objects indexed by id — used to resolve itemTemplate events. */
  private skillsById = new Map<string, Skill>()

  constructor() {
    this.skills$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (skills) => {
        this.skillsById = new Map(skills.map((s: Skill) => [s.id ?? '', s]))
        this.isLoading.set(false)
      },
      error: () => this.isLoading.set(false),
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'admin'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', 'admin', 'skills', 'new'])
  }

  protected deleteSkill(skill: Skill): void {
    this.errorMessage.set(null)
    this.skillState
      .delete(skill.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: () => this.refresh$.next(),
        error: (err: { status?: number }) =>
          this.errorMessage.set(
            err.status === 403
              ? `Deleting "${skill.name}" requires platform administrator rights.`
              : `Deleting "${skill.name}" failed.`
          ),
      })
  }

  protected resolveSkill(id: string): Skill | null {
    return this.skillsById.get(id) ?? null
  }
}
