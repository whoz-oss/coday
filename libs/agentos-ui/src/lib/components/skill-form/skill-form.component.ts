import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router, RouterLink } from '@angular/router'
import { Skill } from '@whoz-oss/agentos-api-client'
import { SkillStateService } from '../../services/skill-state.service'

/** One editable resource file: a relative path and its text content. */
interface ResourceRow {
  path: FormControl<string>
  content: FormControl<string>
}

/**
 * SkillFormComponent — create/edit form for database-backed skills.
 *
 * Serves four routes; the scope is inferred from the presence of `:namespaceId`:
 * - `/:namespaceId/skills/new` and `/:namespaceId/skills/:skillId/edit`
 * - `/admin/skills/new` and `/admin/skills/:skillId/edit`
 *
 * Filesystem-backed skills are read-only: the API rejects writes on them, so in
 * edit mode such a skill redirects to the inspect view instead of rendering a
 * form the user could not submit.
 */
@Component({
  selector: 'agentos-skill-form',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './skill-form.component.html',
  styleUrl: './skill-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SkillFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly skillState = inject(SkillStateService)

  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined

  /** True when editing a platform-level skill (no namespaceId in route). */
  protected readonly isPlatformMode = !this.namespaceId

  protected readonly isEditMode = signal(false)
  protected readonly isLoading = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly errorMessage = signal<string | null>(null)

  /** Kept for the update payload, so server-managed fields survive a save. */
  private existingSkill: Skill | null = null

  /**
   * Name rules mirror `SkillFileParser` server-side (letters, digits, dash, dot,
   * underscore, single spaces between words, 100 chars) so a typo fails here
   * rather than as a 400 after a round-trip.
   */
  protected readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [
        Validators.required,
        Validators.maxLength(100),
        Validators.pattern(/^[a-zA-Z0-9_.-]+( [a-zA-Z0-9_.-]+)*$/),
      ],
    }),
    description: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(500), Validators.pattern(/\S/)],
    }),
    body: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.pattern(/\S/)],
    }),
  })

  protected readonly resourceRows = signal<ResourceRow[]>([])

  protected get nameControl(): FormControl<string> {
    return this.form.controls.name
  }

  protected get descriptionControl(): FormControl<string> {
    return this.form.controls.description
  }

  protected get bodyControl(): FormControl<string> {
    return this.form.controls.body
  }

  ngOnInit(): void {
    const skillId = this.route.snapshot.paramMap.get('skillId')
    if (!skillId) return

    this.isEditMode.set(true)
    this.isLoading.set(true)
    this.skillState
      .getById(skillId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (skill) => {
          // A filesystem skill cannot be written through the API: send the user to
          // the read-only view rather than to a form whose save would be refused.
          if (skill.readOnly) {
            this.router.navigate(this.inspectRoute())
            return
          }
          this.existingSkill = skill
          this.form.patchValue({
            name: skill.name,
            description: skill.description,
            body: skill.body,
          })
          this.resourceRows.set(
            Object.entries(skill.resources ?? {}).map(([path, content]) => ({
              path: new FormControl<string>(path, { nonNullable: true }),
              content: new FormControl<string>(content, { nonNullable: true }),
            }))
          )
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  protected addResource(): void {
    this.resourceRows.update((rows) => [
      ...rows,
      {
        path: new FormControl<string>('', { nonNullable: true }),
        content: new FormControl<string>('', { nonNullable: true }),
      },
    ])
  }

  protected removeResource(row: ResourceRow): void {
    this.resourceRows.update((rows) => rows.filter((r) => r !== row))
  }

  /**
   * Builds the resources map.
   *
   * Always returns an object, never undefined: the server applies
   * `resources = payload.resources ?: existing.resources`, so omitting the field
   * would silently keep the previous map and make removal impossible. An empty
   * object is how a skill's resources get cleared.
   */
  protected buildResourcesPayload(): { [key: string]: string } {
    const entries = this.resourceRows()
      .map((r) => [r.path.value.trim(), r.content.value] as const)
      .filter(([path]) => path.length > 0)
    return Object.fromEntries(entries)
  }

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    this.isSubmitting.set(true)
    this.errorMessage.set(null)

    const payload = {
      ...(this.existingSkill ?? {}),
      namespaceId: this.namespaceId ?? null,
      name: this.nameControl.value.trim(),
      description: this.descriptionControl.value.trim(),
      body: this.bodyControl.value,
      resources: this.buildResourcesPayload(),
    } as Skill

    const call$ = this.isEditMode()
      ? this.skillState.update(this.existingSkill?.id ?? '', payload)
      : this.skillState.create(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: (err: { status?: number }) => {
        this.isSubmitting.set(false)
        if (err.status === 409) {
          this.errorMessage.set(`A skill named "${this.nameControl.value.trim()}" already exists in this scope.`)
        } else if (err.status === 400) {
          this.errorMessage.set('The server rejected this skill. A filesystem-backed skill cannot be modified.')
        } else {
          this.errorMessage.set('Saving the skill failed.')
        }
      },
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  /** Route to the read-only view — only valid in edit mode. */
  protected inspectRoute(): string[] {
    const skillId = this.route.snapshot.paramMap.get('skillId') ?? ''
    if (this.isPlatformMode) {
      return ['/agentos', 'admin', 'skills', skillId, 'inspect']
    }
    return ['/agentos', this.namespaceId!, 'skills', skillId, 'inspect']
  }

  private navigateBack(): void {
    if (this.isPlatformMode) {
      this.router.navigate(['/agentos', 'admin', 'skills'])
    } else {
      this.router.navigate(['/agentos', this.namespaceId!, 'skills'])
    }
  }
}
