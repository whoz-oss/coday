import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { HttpErrorResponse } from '@angular/common/http'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { Prompt, PromptParameter } from '@whoz-oss/agentos-api-client'
import { PromptStateService } from '../../services/prompt-state.service'

/**
 * PromptFormComponent — full-page create / edit form for a prompt.
 *
 * Mode is determined by the presence of `:promptId` in the route params:
 * - `/:namespaceId/prompts/new`              → create mode (namespace scope)
 * - `/:namespaceId/prompts/:promptId/edit`   → edit mode (namespace scope)
 * - `/admin/prompts/new`                     → create mode (platform scope, no namespaceId)
 * - `/admin/prompts/:promptId/edit`          → edit mode (platform scope)
 *
 * Platform mode is detected when `namespaceId` is absent from the route params.
 * In platform mode, `namespaceId` is omitted from the payload (platform scope).
 * On success or cancel, navigates back to the appropriate list route.
 *
 * ## Content section
 *
 * The `content` field is an ordered list of free-text strings. The user can add
 * and remove entries (minimum 1 must remain). Each entry is a multiline textarea
 * supporting @AgentName references and {{paramName}} interpolations.
 * Blank entries are rejected by the backend — inline validation surfaces this before submit.
 *
 * ## Parameters section
 *
 * The `parameters` field is a dynamic list of named placeholders. Each parameter
 * has a required name, optional description, and optional defaultValue (null =
 * required at execution time).
 */
@Component({
  selector: 'agentos-prompt-form',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './prompt-form.component.html',
  styleUrl: './prompt-form.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PromptFormComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly promptState = inject(PromptStateService)

  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined
  /** True when loaded from /admin/prompts — platform scope, no namespaceId in route. */
  protected readonly isPlatformMode = !this.namespaceId

  // ---------------------------------------------------------------------------
  // Form structure
  // ---------------------------------------------------------------------------

  protected readonly form = new FormGroup({
    name: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.minLength(1)],
    }),
    description: new FormControl<string | null>(null),
    content: new FormArray<FormControl<string>>([this.createContentControl()], { validators: [Validators.required] }),
    parameters: new FormArray<FormGroup<ParameterGroup>>([]),
  })

  protected get nameControl() {
    return this.form.controls.name
  }

  protected get descriptionControl() {
    return this.form.controls.description
  }

  protected get contentArray() {
    return this.form.controls.content
  }

  protected get parametersArray() {
    return this.form.controls.parameters
  }

  // ---------------------------------------------------------------------------
  // UI state signals
  // ---------------------------------------------------------------------------

  protected readonly isEditMode = signal(false)
  protected readonly isSubmitting = signal(false)
  protected readonly isLoading = signal(false)
  protected readonly errorMessage = signal<string | null>(null)

  /** Displayed literally in the hint text — avoids Angular interpolation parsing issues. */
  protected readonly paramPlaceholderSyntax = '{{paramName}}'

  /** Kept for the update payload (preserves server-side fields like id). */
  private existingPrompt: Prompt | null = null

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  ngOnInit(): void {
    const promptId = this.route.snapshot.paramMap.get('promptId')
    if (promptId) {
      this.isEditMode.set(true)
      this.loadPrompt(promptId)
    }
  }

  private loadPrompt(id: string): void {
    this.isLoading.set(true)
    this.promptState
      .getById(id)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (prompt) => {
          this.existingPrompt = prompt
          this.hydrateForm(prompt)
          this.isLoading.set(false)
        },
        error: () => {
          this.isLoading.set(false)
          this.navigateBack()
        },
      })
  }

  /** Populate the reactive form from an existing Prompt. */
  private hydrateForm(prompt: Prompt): void {
    this.nameControl.setValue(prompt.name)
    this.descriptionControl.setValue(prompt.description ?? null)

    // Rebuild content FormArray
    this.contentArray.clear()
    const contentEntries = prompt.content.length > 0 ? prompt.content : ['']
    contentEntries.forEach((entry) => this.contentArray.push(this.createContentControl(entry)))

    // Rebuild parameters FormArray
    this.parametersArray.clear()
    ;(prompt.parameters ?? []).forEach((param) => this.parametersArray.push(this.createParameterGroup(param)))
  }

  // ---------------------------------------------------------------------------
  // Content array helpers
  // ---------------------------------------------------------------------------

  private createContentControl(value = ''): FormControl<string> {
    return new FormControl<string>(value, {
      nonNullable: true,
      // Validators.required rejects empty strings; pattern rejects whitespace-only.
      validators: [Validators.required, Validators.pattern(/\S/)],
    })
  }

  protected addContentEntry(): void {
    this.contentArray.push(this.createContentControl())
  }

  protected removeContentEntry(index: number): void {
    if (this.contentArray.length > 1) {
      this.contentArray.removeAt(index)
    }
  }

  // ---------------------------------------------------------------------------
  // Parameters array helpers
  // ---------------------------------------------------------------------------

  private createParameterGroup(param?: Partial<PromptParameter>): FormGroup<ParameterGroup> {
    return new FormGroup<ParameterGroup>({
      name: new FormControl<string>(param?.name ?? '', {
        nonNullable: true,
        validators: [Validators.required, Validators.minLength(1)],
      }),
      description: new FormControl<string | null>(param?.description ?? null),
      defaultValue: new FormControl<string>(param?.defaultValue ?? '', {
        nonNullable: true,
        validators: [Validators.required],
      }),
    })
  }

  protected addParameter(): void {
    this.parametersArray.push(this.createParameterGroup())
  }

  protected removeParameter(index: number): void {
    this.parametersArray.removeAt(index)
  }

  // ---------------------------------------------------------------------------
  // Submit / Cancel
  // ---------------------------------------------------------------------------

  protected submit(): void {
    if (this.form.invalid || this.isSubmitting()) return

    this.isSubmitting.set(true)

    const payload: Prompt = {
      ...(this.existingPrompt ?? {}),
      namespaceId: this.namespaceId,
      name: this.nameControl.value.trim(),
      description: this.descriptionControl.value?.trim() || undefined,
      // No client-side blank filter: Validators.pattern(/\S/) already prevents submit
      // when any entry is whitespace-only. The raw trimmed value is sent as-is so the
      // backend sees exactly what the user typed.
      content: this.contentArray.controls.map((ctrl) => ctrl.value.trim()),
      parameters: this.parametersArray.controls.map((group) => ({
        name: group.controls.name.value.trim(),
        description: group.controls.description.value?.trim() || undefined,
        defaultValue: group.controls.defaultValue.value.trim(),
      })),
    }

    const call$ =
      this.isEditMode() && this.existingPrompt?.id
        ? this.promptState.update(this.existingPrompt.id, payload)
        : this.promptState.create(payload)

    this.errorMessage.set(null)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: (err: HttpErrorResponse) => {
        this.isSubmitting.set(false)
        if (err.status === 409) {
          this.errorMessage.set(`A prompt named "${payload.name}" already exists in this scope.`)
        } else if (err.status === 400) {
          this.errorMessage.set(err.error?.message ?? 'Invalid prompt data.')
        } else {
          this.errorMessage.set('An unexpected error occurred. Please try again.')
        }
      },
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    if (this.isPlatformMode) {
      this.router.navigate(['/agentos', 'admin', 'prompts'])
    } else {
      this.router.navigate(['/agentos', this.namespaceId, 'prompts'])
    }
  }
}

/** Typed shape for a single parameter FormGroup. */
interface ParameterGroup {
  name: FormControl<string>
  description: FormControl<string | null>
  defaultValue: FormControl<string>
}
