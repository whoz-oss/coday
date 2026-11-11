import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router } from '@angular/router'
import { AgentConfig, Skill } from '@whoz-oss/agentos-api-client'
import { ButtonDirective, SectionHeaderComponent, TableDirective, TagComponent } from '@whoz-oss/design-system'
import { BehaviorSubject, catchError, forkJoin, map, of, switchMap } from 'rxjs'
import { AgentStateService } from '../../services/agent-state.service'
import { skillGrantOf } from '../../services/skill-selector.utils'
import { SkillStateService } from '../../services/skill-state.service'
import { createRowFilter } from '../../utils/filter-rows'
import { createRowDeletionState } from '../../utils/row-deletion-state'
import { RowDeleteActionComponent } from '../row-delete-action/row-delete-action.component'
import { TableFilterComponent } from '../table-filter/table-filter.component'

interface SkillRow {
  key: string
  skill: Skill
  scope: 'platform' | 'namespace'
  /** Agents naming this skill in their selectors (named grants only, `*` is not counted). */
  namedByAgents: number
}

/**
 * NamespaceSkillsComponent — table view for the skills of a namespace.
 *
 * Loaded at /:namespaceId/skills. Responsibilities:
 * - Load namespace-level AND platform-level skills and merge them (platform first)
 * - Platform-level skills are read-only (no edit/delete), as are filesystem-backed `SKILL.md` skills
 * - Deletion with the inline two-step confirmation shared by every list
 *
 * The namespace list already contains filesystem-backed skills: the backend merges
 * `SKILL.md` discovery into `listByParentSkill`, flagging those entries `readOnly`.
 * The platform list never does — filesystem discovery is namespace-scoped.
 *
 * Agents are loaded for one reason: counting how many of them name a skill, so the row can say
 * what a deletion would break. Their failure is non-fatal: the list still renders without it.
 *
 * Create and edit are handled by SkillFormComponent on dedicated routes.
 */
@Component({
  selector: 'agentos-namespace-skills',
  imports: [
    ButtonDirective,
    SectionHeaderComponent,
    TableDirective,
    TagComponent,
    TableFilterComponent,
    RowDeleteActionComponent,
  ],
  templateUrl: './namespace-skills.component.html',
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
  protected readonly deletion = createRowDeletionState()
  protected readonly rows = signal<SkillRow[]>([])
  protected readonly rowFilter = createRowFilter(this.rows, (row) =>
    [row.skill.name, row.skill.description, row.scope].filter(Boolean).join(' ')
  )

  constructor() {
    this.refresh$
      .pipe(
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
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe({
        next: ({ platform, namespace, agents }) => {
          const toRow = (skill: Skill, scope: SkillRow['scope'], index: number): SkillRow => ({
            key: `${scope}:${skill.id ?? index}`,
            skill,
            scope,
            namedByAgents: agents.filter((a) => skillGrantOf(a.skillSelectors, skill.name) === 'named').length,
          })
          this.rows.set([
            ...platform.map((skill, index) => toRow(skill, 'platform', index)),
            ...namespace.map((skill, index) => toRow(skill, 'namespace', index)),
          ])
          this.isLoading.set(false)
        },
        error: () => this.isLoading.set(false),
      })
  }

  protected resourceCount(skill: Skill): number {
    return Object.keys(skill.resources ?? {}).length
  }

  /** A platform skill (scope) or a SKILL.md skill (storage origin) cannot be edited nor deleted. */
  protected isMutable(row: SkillRow): boolean {
    return row.scope === 'namespace' && row.skill.readOnly !== true && !!row.skill.id
  }

  protected openCreateForm(): void {
    void this.router.navigate(['/agentos', this.namespaceId, 'skills', 'new'])
  }

  protected inspect(row: SkillRow): void {
    if (!row.skill.id) return
    void this.router.navigate(
      row.scope === 'platform'
        ? ['/agentos', 'admin', 'skills', row.skill.id, 'inspect']
        : ['/agentos', this.namespaceId, 'skills', row.skill.id, 'inspect']
    )
  }

  protected edit(row: SkillRow): void {
    if (!this.isMutable(row) || !row.skill.id) return
    void this.router.navigate(['/agentos', this.namespaceId, 'skills', row.skill.id, 'edit'])
  }

  protected requestDelete(row: SkillRow): void {
    this.deletion.request(row.key)
  }

  protected confirmDelete(row: SkillRow): void {
    if (!this.isMutable(row) || !row.skill.id) return
    const named = row.namedByAgents
    this.deletion.execute(row.key, {
      request$: this.skillState.delete(row.skill.id).pipe(takeUntilDestroyed(this.destroyRef)),
      onSuccess: () => this.refresh$.next(),
      errorMessage: (error) =>
        (error as { status?: number }).status === 400
          ? `"${row.skill.name}" is defined by a SKILL.md file and cannot be deleted through the API.`
          : `Deleting "${row.skill.name}" failed.${named > 0 ? ` ${named} agent(s) still name it.` : ''}`,
    })
  }
}
