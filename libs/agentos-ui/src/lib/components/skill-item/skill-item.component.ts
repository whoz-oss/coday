import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core'
import { Router } from '@angular/router'
import { Skill } from '@whoz-oss/agentos-api-client'
import { IconButtonComponent, KebabMenuComponent, KebabMenuItem } from '@whoz-oss/design-system'

/**
 * SkillItemComponent — presentational component for a single skill card.
 *
 * Displays the name, the description and, when the skill bundles resource files,
 * their count. A YAML badge marks filesystem-backed skills.
 *
 * Two independent reasons make a skill unmodifiable, and both collapse the kebab
 * menu to "View skill":
 * - [readOnly]: a platform skill shown in a namespace context (scope).
 * - `skill().readOnly`: a filesystem-backed `SKILL.md`, which the API refuses to
 *   update or delete (storage origin).
 *
 * Unlike PromptItemComponent, the read-only case keeps a reduced menu rather than
 * hiding every action — a skill the user cannot edit is still worth reading.
 */
@Component({
  selector: 'agentos-skill-item',
  imports: [KebabMenuComponent, IconButtonComponent],
  templateUrl: './skill-item.component.html',
  styleUrl: './skill-item.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SkillItemComponent {
  private readonly router = inject(Router)

  readonly skill = input.required<Skill>()
  readonly namespaceId = input<string | undefined>(undefined)
  /** When true, navigation targets the admin platform routes instead of the namespace ones. */
  readonly platformMode = input(false)
  /** When true, the skill is shown read-only because of its scope (platform seen from a namespace). */
  readonly readOnly = input(false)
  /**
   * How many agents name this skill in their selectors, so the deletion prompt can say what
   * it would break. Agents that take the whole catalogue through `*` are not counted: they
   * keep working with one skill less. Left at 0 where the count cannot be computed, such as
   * the platform list, whose consumers span every namespace.
   */
  readonly namedByAgents = input(0)

  readonly deleteRequested = output<Skill>()

  protected readonly pendingDelete = signal(false)

  /** True when no mutation is offered, whatever the reason (scope or storage origin). */
  protected readonly isImmutable = computed(() => this.readOnly() || this.skill().readOnly === true)

  /** Number of bundled resource files, or 0 when the skill has none. */
  protected readonly resourceCount = computed(() => Object.keys(this.skill().resources ?? {}).length)

  protected readonly menuItems: KebabMenuItem[] = [
    { key: 'inspect', label: 'View skill', icon: 'search' },
    { key: 'edit', label: 'Edit skill', icon: 'edit' },
    { key: 'delete', label: 'Delete skill', icon: 'delete', variant: 'danger' },
  ]

  protected readonly readOnlyMenuItems: KebabMenuItem[] = [{ key: 'inspect', label: 'View skill', icon: 'search' }]

  protected onMenuAction(key: string): void {
    switch (key) {
      case 'inspect':
        this.router.navigate(this.route('inspect'))
        break
      case 'edit':
        this.router.navigate(this.route('edit'))
        break
      case 'delete':
        this.pendingDelete.set(true)
        break
    }
  }

  protected onDeleteConfirmed(): void {
    this.pendingDelete.set(false)
    this.deleteRequested.emit(this.skill())
  }

  protected onDeleteCancelled(): void {
    this.pendingDelete.set(false)
  }

  private route(action: 'inspect' | 'edit'): string[] {
    const id = this.skill().id ?? ''
    if (this.platformMode()) {
      return ['/agentos', 'admin', 'skills', id, action]
    }
    return ['/agentos', this.namespaceId() ?? '', 'skills', id, action]
  }
}
