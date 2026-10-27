import { AsyncPipe } from '@angular/common'
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, Skill } from '@whoz-oss/agentos-api-client'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, map, of, shareReplay, switchMap } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { skillGrantOf } from '../../services/skill-selector.utils'
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
  private readonly agentState = inject(AgentStateService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

  private readonly refresh$ = new BehaviorSubject<void>(undefined)

  protected readonly isLoading = signal(true)

  /** Set when a deletion is refused, so the failure is not swallowed. */
  protected readonly errorMessage = signal<string | null>(null)

  /**
   * Fetches both skill levels and the namespace's agents in parallel.
   *
   * The agents are loaded for one reason: counting how many of them name a skill, so the
   * deletion prompt can say what it would break. Their failure is non-fatal — the list
   * still renders, just without that count.
   */
  private readonly allSkills$ = this.refresh$.pipe(
    switchMap(() => {
      this.isLoading.set(true)
      return forkJoin({
        platform: this.skillState.listPlatform().pipe(catchError(() => of([] as Skill[]))),
        namespace: this.skillState.listByNamespace(this.namespaceId),
        agents: forkJoin({
          namespace: this.agentState.listByNamespace(this.namespaceId),
          platform: this.agentState.listPlatform().pipe(catchError(() => of([] as AgentConfig[]))),
        }).pipe(
          map(({ namespace, platform }) => [...namespace, ...platform]),
          catchError(() => of([] as AgentConfig[]))
        ),
      })
    }),
    // Subscribed twice — by the async pipe and by the constructor, which keeps the full
    // objects for the item template — so without this the whole load runs twice per refresh.
    shareReplay({ bufferSize: 1, refCount: true })
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

  /** Agents naming each skill, by lower-cased skill name. */
  private namedByAgentsByName = new Map<string, number>()

  constructor() {
    this.allSkills$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ({ platform, namespace, agents }) => {
        this.platformSkillsById = new Map(platform.map((s: Skill) => [s.id ?? '', s]))
        this.namespaceSkillsById = new Map(namespace.map((s: Skill) => [s.id ?? '', s]))
        this.namedByAgentsByName = this.countNamedBy([...platform, ...namespace], agents)
        this.isLoading.set(false)
      },
      error: () => this.isLoading.set(false),
    })
  }

  /**
   * Count, per skill, the agents that name it in their selectors.
   *
   * Only named grants count: an agent granted the whole catalogue through `*` loses nothing
   * identifiable when one skill goes away, so warning about it would cry wolf.
   */
  private countNamedBy(skills: Skill[], agents: AgentConfig[]): Map<string, number> {
    const counts = new Map<string, number>()
    for (const skill of skills) {
      const named = agents.filter((a) => skillGrantOf(a.skillSelectors, skill.name) === 'named').length
      if (named > 0) counts.set(skill.name.toLowerCase(), named)
    }
    return counts
  }

  protected namedByAgents(skill: Skill): number {
    return this.namedByAgentsByName.get(skill.name.toLowerCase()) ?? 0
  }

  protected goBack(): void {
    this.router.navigate(['/agentos', 'namespaces'])
  }

  protected openCreateForm(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'skills', 'new'])
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
            err.status === 400
              ? `"${skill.name}" is defined by a SKILL.md file and cannot be deleted through the API.`
              : `Deleting "${skill.name}" failed.`
          ),
      })
  }

  protected resolveSkill(id: string): Skill | null {
    return this.platformSkillsById.get(id) ?? this.namespaceSkillsById.get(id) ?? null
  }

  protected isPlatformSkill(id: string): boolean {
    return this.platformSkillsById.has(id)
  }
}
