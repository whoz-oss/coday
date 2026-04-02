import { Component, DestroyRef, computed, effect, inject, input, output, untracked } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { FormControl, ReactiveFormsModule, UntypedFormGroup } from '@angular/forms'
import { JsonSchemaFieldComponent } from './json-schema-field.component'
import { JsonSchemaObject } from './json-schema.model'

/**
 * ds-json-schema-form — renders a dynamic form driven by a JSON Schema.
 *
 * When `schema` is null or has no `properties`, the component renders nothing.
 * The host should hide or replace the component when schema is null.
 *
 * `valueChange` always emits the current form value as-is (no coercion, no
 * validation). It emits `null` only when the schema has no properties.
 *
 * Supports nested objects: if a property has type=object with `properties`,
 * it renders a nested ds-json-schema-form recursively.
 *
 * Supports arrays: if a property has type=array, it renders a list of typed
 * item inputs with add / remove controls.
 *
 * CSS contract: --color-text-secondary, --color-border, --color-text,
 *               --color-bg-secondary, --color-border-light, --color-code-bg,
 *               --color-primary
 *
 * @example
 * <ds-json-schema-form
 *   [schema]="selectedType.configSchema"
 *   [value]="currentParams"
 *   (valueChange)="onParamsChange($event)"
 * />
 */
@Component({
  selector: 'ds-json-schema-form',
  standalone: true,
  imports: [ReactiveFormsModule, JsonSchemaFieldComponent],
  templateUrl: './json-schema-form.component.html',
  styleUrl: './json-schema-form.component.scss',
})
export class JsonSchemaFormComponent {
  private readonly destroyRef = inject(DestroyRef)

  readonly schema = input<JsonSchemaObject | null>(null)
  readonly value = input<Record<string, unknown> | null>(null)

  readonly valueChange = output<Record<string, unknown> | null>()

  // UntypedFormGroup is Angular's intended solution for fully dynamic
  // control maps where the set of keys is not known at compile time.
  protected readonly formGroup = new UntypedFormGroup({})

  protected readonly fieldKeys = computed(() => {
    const s = this.schema()
    if (!s?.properties) return []
    return Object.keys(s.properties)
  })

  protected readonly hasFields = computed(() => this.fieldKeys().length > 0)

  constructor() {
    // React only to schema changes — NOT to value changes.
    // Reading value() here is intentional: we want the initial value at the
    // moment the schema is set. But we use untracked() so that subsequent
    // value() updates don't re-trigger this effect and rebuild the form,
    // which would cause an infinite loop with nested forms:
    //   valueChange -> setValue -> valueChanges -> emitValue -> valueChange ...
    effect(() => {
      const schema = this.schema()
      const initialValue = untracked(() => this.value())
      this.rebuildForm(schema, initialValue)
    })
  }

  private rebuildForm(schema: JsonSchemaObject | null, currentValue: Record<string, unknown> | null): void {
    Object.keys(this.formGroup.controls).forEach((key) => {
      this.formGroup.removeControl(key)
    })

    if (!schema?.properties) {
      this.valueChange.emit(null)
      return
    }

    for (const [key, fieldSchema] of Object.entries(schema.properties)) {
      const initialValue = currentValue?.[key] ?? fieldSchema.default ?? null
      this.formGroup.addControl(key, new FormControl<unknown>(initialValue))
    }

    // takeUntilDestroyed ensures cleanup when the component is destroyed.
    // A new subscription is created on each rebuildForm call; the previous
    // one is already cleaned up because the old formGroup controls were
    // removed and replaced — but we rely on takeUntilDestroyed as the
    // safety net for the final subscription.
    this.formGroup.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.emitValue())
    this.emitValue()
  }

  private emitValue(): void {
    const schema = this.schema()
    if (!schema?.properties) {
      this.valueChange.emit(null)
      return
    }
    this.valueChange.emit(this.formGroup.value as Record<string, unknown>)
  }

  protected getFieldSchema(key: string): JsonSchemaObject {
    return this.schema()?.properties?.[key] ?? {}
  }

  protected isRequired(key: string): boolean {
    return this.schema()?.required?.includes(key) ?? false
  }

  protected getControl(key: string): FormControl {
    return this.formGroup.get(key) as FormControl
  }
}
