import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { Skill } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, map, of, switchMap } from 'rxjs'
import { SkillStateService } from '../../services/skill-state.service'
import { SkillItemComponent } from '../skill-item/skill-item.component'

const GROUP_PLATFORM = 'platform'
const GROUP_NAMESPACE = 'namespace'

/**
 * NamespaceSkillsComponent — list view for the skills of a namespace.
 *
 * Loaded at /:namespaceId/skills. Responsibilities:
 * - Load namespace-level AND platform-level skills
 * - Merge both levels into a grouped ds-entity-list (platform first, then namespace)
 * - Platform-level skills are displayed read-only (no edit/delete actions)
 * - Deletion with inline two-step confirmation (delegated to SkillItemComponent)
 *
 * The namespace list already contains filesystem-backed skills: the backend merges
 * `SKILL.md` discovery into `listByParentSkill`, flagging those entries `readOnly`.
 * The platform list never does — filesystem discovery is namespace-scoped.
 *
 * Create and edit are handled by SkillFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-namespace-skills',
  imports: [AsyncPipe, EntityListComponent, SkillItemComponent],
  templateUrl: './namespace-skills.component.html',
  styleUrl: './namespace-skills.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class NamespaceSkillsComponent {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly skillState = inject(SkillStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)

  /** Fetches both namespace-level and platform-level skills in parallel. */
  private readonly allSkills$ = this.refresh$.pipe(
    switchMap(() => {
      this.isLoading.set(true)
      return forkJoin({
        platform: this.skillState.listPlatform().pipe(catchError(() => of([] as Skill[]))),
        namespace: this.skillState.listByNamespace(this.namespaceId),
      })
    })
  )

  /** Mapped to EntityListItem[] for ds-entity-list, platform group first. */
  protected readonly skillItems$ = this.allSkills$.pipe(
    map(({ platform, namespace }) => [
      ...platform.map(
        (s: Skill): EntityListItem => ({
          id: s.id ?? '',
          name: s.name,
          description: s.description,
          groupKey: GROUP_PLATFORM,
          groupLabel: 'Platform (read-only)',
        })
      ),
      ...namespace.map(
        (s: Skill): EntityListItem => ({
          id: s.id ?? '',
          name: s.name,
          description: s.description,
          groupKey: GROUP_NAMESPACE,
          groupLabel: 'Namespace',
        })
      ),
    ])
  )

  /** Full skill objects indexed by id — used to resolve itemTemplate events. */
  private namespaceSkillsById = new Map<string, Skill>()
  private platformSkillsById = new Map<string, Skill>()

  constructor() {
    this.allSkills$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ platform, namespace }) => {
        this.platformSkillsById = new Map(platform.map((s: Skill) => [s.id ?? '', s]))
        this.namespaceSkillsById = new Map(namespace.map((s: Skill) => [s.id ?? '', s]))
        this.isLoading.set(false)
      },
      error: () => this.isLoading.set(false),
    })
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'skills', 'new'])
  }

  protected deleteSkill(skill: Skill): void {
    this.skillState
      .delete(skill.id ?? '')
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.refresh$.next())
  }

  protected resolveSkill(id: string): Skill | null {
    return this.platformSkillsById.get(id) ?? this.namespaceSkillsById.get(id) ?? null
  }

  protected isPlatformSkill(id: string): boolean {
    return this.platformSkillsById.has(id)
  }
}
