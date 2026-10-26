import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router, RouterLink } from '@angular/router'
import { AgentConfig, Skill } from '@whoz-oss/agentos-api-client'
import { catchError, forkJoin, of } from 'rxjs'
import { MarkdownPipe } from '../../pipes/markdown.pipe'
import { AgentStateService } from '../../services/agent-state.service'
import { skillGrantOf } from '../../services/skill-selector.utils'
import { SkillStateService } from '../../services/skill-state.service'

/** An agent whose selectors reach this skill, and how. */
interface SkillConsumer {
  name: string
  /** `named` breaks when the skill goes away; `all` just resolves to one skill less. */
  grant: 'named' | 'all'
  /** Platform-level agent, deployable to any namespace. */
  platform: boolean
}

/**
 * SkillInspectComponent — read-only view of a single skill.
 *
 * Shows the metadata, the markdown body as the agent receives it, and every
 * bundled resource file. This is the only place a skill's content is readable
 * from the UI: `readSkill` / `readSkillResource` are agent tools, not endpoints.
 *
 * It also answers the reverse question the agent form cannot: which agents use
 * this skill. That list is computed client-side from the namespace's agent configs,
 * because no endpoint queries agents by selector.
 *
 * Routes:
 * - `/:namespaceId/skills/:skillId/inspect`
 * - `/admin/skills/:skillId/inspect`
 */
@Component({
  selector: 'agentos-skill-inspect',
  imports: [RouterLink, MarkdownPipe],
  templateUrl: './skill-inspect.component.html',
  styleUrl: './skill-inspect.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SkillInspectComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly skillState = inject(SkillStateService)
  private readonly agentState = inject(AgentStateService)

  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined
  protected readonly skillId = this.route.snapshot.params['skillId'] as string

  /** True when viewing from the platform admin section (no namespaceId in route). */
  protected readonly isPlatformMode = !this.namespaceId

  protected readonly isLoading = signal(true)
  protected readonly hasError = signal(false)
  protected readonly skill = signal<Skill | null>(null)

  protected readonly bodyCollapsed = signal(false)

  /** Tracks which resource rows are expanded (by path). */
  protected readonly expandedResources = signal<Set<string>>(new Set())

  /** Resource paths, sorted, for a stable rendering order. */
  protected readonly resourcePaths = computed(() => Object.keys(this.skill()?.resources ?? {}).sort())

  /**
   * True for a filesystem-backed skill. Its audit fields carry no meaning — the
   * entity is rebuilt in memory on every read, so the timestamps are the read
   * time — and it cannot be edited, so no Edit link is offered.
   */
  protected readonly isFilesystemBacked = computed(() => this.skill()?.readOnly === true)

  /** Agents whose selectors reach this skill, named grants first. */
  protected readonly consumers = signal<SkillConsumer[]>([])

  /**
   * True when the agent configs could not be read. The usage list is then unknown
   * rather than empty, which is the difference between "no agent uses this" and
   * "we could not tell" — and only the first makes a deletion safe.
   */
  protected readonly consumersUnavailable = signal(false)

  /** True while the usage list is still loading, after the skill itself has rendered. */
  protected readonly consumersLoading = signal(false)

  ngOnInit(): void {
    this.skillState
      .getById(this.skillId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (skill) => {
          this.skill.set(skill)
          this.isLoading.set(false)
          this.loadConsumers(skill.name)
        },
        error: () => {
          this.hasError.set(true)
          this.isLoading.set(false)
        },
      })
  }

  /**
   * Resolve which agents reference [skillName].
   *
   * Namespace scope only: a platform skill viewed from the admin section is visible to
   * every namespace, and listing its consumers would mean walking all of them. The
   * namespace list is paired with the platform agents, which deploy into the namespace
   * and can name the skill too.
   */
  private loadConsumers(skillName: string): void {
    if (!this.namespaceId) return

    this.consumersLoading.set(true)
    forkJoin({
      namespace: this.agentState.listByNamespace(this.namespaceId),
      platform: this.agentState.listPlatform().pipe(catchError(() => of([] as AgentConfig[]))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ namespace, platform }) => {
          const consumers = [
            ...this.toConsumers(namespace, skillName, false),
            ...this.toConsumers(platform, skillName, true),
          ]
          // Named grants first: those are the references a deletion would leave dangling.
          this.consumers.set([
            ...consumers.filter((c) => c.grant === 'named'),
            ...consumers.filter((c) => c.grant === 'all'),
          ])
          this.consumersLoading.set(false)
        },
        error: () => {
          this.consumersUnavailable.set(true)
          this.consumersLoading.set(false)
        },
      })
  }

  private toConsumers(configs: AgentConfig[], skillName: string, platform: boolean): SkillConsumer[] {
    return configs
      .map((config) => ({ config, grant: skillGrantOf(config.skillSelectors, skillName) }))
      .filter((entry) => entry.grant !== 'none')
      .map(({ config, grant }) => ({ name: config.name, grant: grant as 'named' | 'all', platform }))
  }

  protected toggleBody(): void {
    this.bodyCollapsed.update((v) => !v)
  }

  protected toggleResource(path: string): void {
    this.expandedResources.update((current) => {
      const next = new Set(current)
      if (next.has(path)) {
        next.delete(path)
      } else {
        next.add(path)
      }
      return next
    })
  }

  protected isResourceExpanded(path: string): boolean {
    return this.expandedResources().has(path)
  }

  protected resourceContent(path: string): string {
    return this.skill()?.resources?.[path] ?? ''
  }

  protected editRoute(): string[] {
    if (this.isPlatformMode) {
      return ['/agentos', 'admin', 'skills', this.skillId, 'edit']
    }
    return ['/agentos', this.namespaceId!, 'skills', this.skillId, 'edit']
  }

  protected back(): void {
    if (this.isPlatformMode) {
      this.router.navigate(['/agentos', 'admin', 'skills'])
    } else {
      this.router.navigate(['/agentos', this.namespaceId!, 'skills'])
    }
  }
}
