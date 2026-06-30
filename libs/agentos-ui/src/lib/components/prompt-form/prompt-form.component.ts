import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormArray, FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms'
import { ActivatedRoute, Router } from '@angular/router'
import { Prompt, PromptControllerService, PromptParameter } from '@whoz-oss/agentos-api-client'

/**
 * PromptFormComponent — full-page create / edit form for a prompt.
 *
 * Mode is determined by the presence of `:promptId` in the route params:
 * - `/:namespaceId/prompts/new`              → create mode
 * - `/:namespaceId/prompts/:promptId/edit`   → edit mode
 *
 * The namespaceId is fixed at creation time and never exposed as an editable field.
 * On success or cancel, navigates back to /:namespaceId/prompts.
 *
 * ## Content section
 *
 * The `content` field is an ordered list of free-text strings. The user can add
 * and remove entries (minimum 1 must remain). Each entry is a multiline textarea
 * supporting @AgentName references and {{paramName}} interpolations.
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
  private readonly promptController = inject(PromptControllerService)

  protected readonly namespaceId = this.route.snapshot.params['namespaceId'] as string

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
    this.promptController
      .getByIdPrompt(id)
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
      validators: [Validators.required],
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
      defaultValue: new FormControl<string | null>(param?.defaultValue ?? null),
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
      content: this.contentArray.controls.map((ctrl) => ctrl.value.trim()).filter((v) => v.length > 0),
      parameters: this.parametersArray.controls.map((group) => ({
        name: group.controls.name.value.trim(),
        description: group.controls.description.value?.trim() || undefined,
        defaultValue: group.controls.defaultValue.value ?? null,
      })),
    }

    const call$ =
      this.isEditMode() && this.existingPrompt?.id
        ? this.promptController.updatePrompt(this.existingPrompt.id, payload)
        : this.promptController.createPrompt(payload)

    call$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => this.navigateBack(),
      error: () => this.isSubmitting.set(false),
    })
  }

  protected cancel(): void {
    this.navigateBack()
  }

  private navigateBack(): void {
    this.router.navigate(['/agentos', this.namespaceId, 'prompts'])
  }
}

/** Typed shape for a single parameter FormGroup. */
interface ParameterGroup {
  name: FormControl<string>
  description: FormControl<string | null>
  defaultValue: FormControl<string | null>
}
