import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router, RouterLink } from '@angular/router'
import { Skill } from '@whoz-oss/agentos-api-client'
import { MarkdownPipe } from '../../pipes/markdown.pipe'
import { SkillStateService } from '../../services/skill-state.service'

/**
 * SkillInspectComponent — read-only view of a single skill.
 *
 * Shows the metadata, the markdown body as the agent receives it, and every
 * bundled resource file. This is the only place a skill's content is readable
 * from the UI: `readSkill` / `readSkillResource` are agent tools, not endpoints.
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

  ngOnInit(): void {
    this.skillState
      .getById(this.skillId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (skill) => {
          this.skill.set(skill)
          this.isLoading.set(false)
        },
        error: () => {
          this.hasError.set(true)
          this.isLoading.set(false)
        },
      })
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
