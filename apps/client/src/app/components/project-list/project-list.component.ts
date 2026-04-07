import { Component, computed, inject } from '@angular/core'
import { Router } from '@angular/router'

import { ProjectStateService } from '../../core/services/project-state.service'
import { toSignal } from '@angular/core/rxjs-interop'
import { EntityListComponent, EntityListItem } from '@whoz-oss/design-system'
import { ProjectInfo } from '../../core/services/project-api.service'
import { GlobalMissionControlComponent } from '../global-mission-control/global-mission-control.component'

/** Separator used in the worktree project naming convention: `parent__subProject` */
const WORKTREE_SEPARATOR = '__'

/**
 * Container (smart) component for the project selection page.
 *
 * Responsibilities:
 * - Reads the project list from ProjectStateService
 * - Parses the worktree naming convention (parent__subProject) to build groups
 * - Maps ProjectInfo to EntityListItem for ds-entity-list
 * - Handles navigation on selection and delegates creation to /project/new
 */
@Component({
  selector: 'app-project-selection',
  standalone: true,
  imports: [EntityListComponent, GlobalMissionControlComponent],
  templateUrl: './project-list.component.html',
  styleUrl: './project-list.component.scss',
})
export class ProjectListComponent {
  private readonly router = inject(Router)
  private readonly projectStateService = inject(ProjectStateService)

  protected readonly projects = toSignal(this.projectStateService.projectList$)
  protected readonly forcedProject = toSignal(this.projectStateService.forcedProject$)

  /**
   * Maps the flat project list to EntityListItem[], with three kinds of groups:
   *
   * - Standalone projects (no worktrees) → gathered in a "Projects" group.
   *
   * - Root projects that have at least one worktree → one group per root,
   *   named after the root. The root card comes first, then its worktrees
   *   sorted alphabetically. Worktree names are shortened (prefix stripped)
   *   and the full name is shown as description.
   *
   * - Orphaned worktrees (root not in the list) → "Orphaned worktrees" group.
   *
   * Volatile projects carry a "temp" badge regardless of group.
   */
  protected readonly projectItems = computed<EntityListItem[]>(() => {
    const projectList = this.projects()
    if (!projectList) return []

    const rootProjects = projectList.filter((p) => !p.name.includes(WORKTREE_SEPARATOR))
    const worktrees = projectList.filter((p) => p.name.includes(WORKTREE_SEPARATOR))

    // Index root project names for O(1) lookup
    const rootNames = new Set(rootProjects.map((p) => p.name))

    /** Extracts the root name from a worktree project name, or null if malformed. */
    const rootOf = (name: string): string | null => name.split(WORKTREE_SEPARATOR)[0] ?? null

    // Determine which roots have at least one worktree
    const rootsWithWorktrees = new Set(
      worktrees.map((p) => rootOf(p.name)).filter((root): root is string => root !== null && rootNames.has(root))
    )

    const items: EntityListItem[] = []

    // ── Root projects with worktrees → one group per root ──────────────────
    const sortedRoots = [...rootsWithWorktrees].sort((a, b) => a.localeCompare(b))

    for (const rootName of sortedRoots) {
      const root = rootProjects.find((p) => p.name === rootName)!
      // Root card first in its group
      items.push({ ...this.toEntityListItem(root), groupKey: rootName, groupLabel: rootName })
      // Worktrees sorted alphabetically, name shortened for readability
      const children = worktrees.filter((p) => rootOf(p.name) === rootName).sort((a, b) => a.name.localeCompare(b.name))
      for (const wt of children) {
        const shortName = wt.name.slice(rootName.length + WORKTREE_SEPARATOR.length)
        items.push({
          ...this.toEntityListItem(wt),
          name: shortName,
          description: wt.name,
          groupKey: rootName,
          groupLabel: rootName,
        })
      }
    }

    // ── Standalone non-volatile projects → shared "Projects" group ────────
    const standalone = [...rootProjects]
      .filter((p) => !rootsWithWorktrees.has(p.name) && !p.volatile)
      .sort((a, b) => a.name.localeCompare(b.name))

    for (const project of standalone) {
      items.push({ ...this.toEntityListItem(project), groupKey: '__standalone__', groupLabel: 'Projects' })
    }

    // ── Volatile projects ── always last ──────────────────────────────────
    const volatile = [...rootProjects]
      .filter((p) => !rootsWithWorktrees.has(p.name) && p.volatile)
      .sort((a, b) => a.name.localeCompare(b.name))

    for (const project of volatile) {
      items.push({ ...this.toEntityListItem(project), groupKey: '__volatile__', groupLabel: 'Temporary' })
    }

    // ── Orphaned worktrees (root missing from list) ────────────────────────
    const orphans = worktrees
      .filter((p) => {
        const root = rootOf(p.name)
        return root !== null && !rootNames.has(root)
      })
      .sort((a, b) => a.name.localeCompare(b.name))

    for (const wt of orphans) {
      items.push({ ...this.toEntityListItem(wt), groupKey: '__orphaned__', groupLabel: 'Orphaned worktrees' })
    }

    return items
  })

  protected readonly showCreateButton = computed(() => !this.forcedProject())

  constructor() {}

  protected onItemSelected(projectName: string): void {
    this.projectStateService.selectProject(projectName)
    this.router.navigate(['project', projectName])
  }

  protected onCreateRequested(): void {
    this.router.navigate(['project', 'new'])
  }

  private toEntityListItem(project: ProjectInfo): EntityListItem {
    return {
      id: project.name,
      name: project.name,
      badges: project.volatile ? [{ label: 'temp', variant: 'warning' }] : [],
    }
  }
}
