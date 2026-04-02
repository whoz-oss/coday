import { Component, computed, effect, input, output, signal, untracked } from '@angular/core'
import { FormControl, ReactiveFormsModule, UntypedFormGroup, Validators } from '@angular/forms'
import { validateJsonSchema } from './ajv-validator'
import { JsonSchemaFieldComponent } from './json-schema-field.component'
import { JsonSchemaObject } from './json-schema.model'

/**
 * ds-json-schema-form — renders a dynamic form driven by a JSON Schema.
 *
 * When `schema` is null or has no `properties`, the component renders nothing.
 * The host should hide or replace the component when schema is null.
 *
 * Validation is performed by Ajv against the full schema on every change.
 * `valueChange` emits the validated value, or `null` when the form is invalid.
 *
 * Supports nested objects: if a property has type=object with `properties`,
 * it renders a nested ds-json-schema-form recursively.
 *
 * CSS contract: --color-error, --color-text-secondary, --color-border,
 *               --color-text, --color-bg-secondary, --color-border-light,
 *               --color-code-bg, --color-primary
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

  /** Per-field validation errors from Ajv, keyed by property name. */
  protected readonly fieldErrors = signal<Map<string, string[]>>(new Map())

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
    this.fieldErrors.set(new Map())

    if (!schema?.properties) {
      this.valueChange.emit(null)
      return
    }

    const required = new Set(schema.required ?? [])

    for (const [key, fieldSchema] of Object.entries(schema.properties)) {
      const isRequired = required.has(key)
      const defaultValue = this.resolveDefault(fieldSchema)
      const initialValue = this.extractInitialValue(currentValue, key, fieldSchema, defaultValue)

      const control = new FormControl<unknown>(initialValue, {
        validators: isRequired ? [Validators.required] : [],
      })
      this.formGroup.addControl(key, control)
    }

    this.formGroup.valueChanges.subscribe(() => this.emitValue())
    this.emitValue()
  }

  private emitValue(): void {
    const schema = this.schema()
    if (!schema?.properties) {
      this.valueChange.emit(null)
      return
    }

    const raw = this.formGroup.value as Record<string, unknown>
    const coerced = this.coerceValues(raw, schema)
    const errors = validateJsonSchema(schema, coerced)

    // Bucket errors by field key
    const errorMap = new Map<string, string[]>()
    for (const msg of errors) {
      // Format: "fieldKey: message" (see ajv-validator.ts)
      const colonIdx = msg.indexOf(': ')
      const field = colonIdx !== -1 ? msg.slice(0, colonIdx) : '(root)'
      const message = colonIdx !== -1 ? msg.slice(colonIdx + 2) : msg
      const existing = errorMap.get(field) ?? []
      errorMap.set(field, [...existing, message])
    }
    this.fieldErrors.set(errorMap)

    if (errors.length === 0) {
      this.valueChange.emit(coerced)
    } else {
      this.valueChange.emit(null)
    }
  }

  private coerceValues(raw: Record<string, unknown>, schema: JsonSchemaObject): Record<string, unknown> {
    const result: Record<string, unknown> = {}
    for (const [key, fieldSchema] of Object.entries(schema.properties ?? {})) {
      const val = raw[key]
      if (val === null || val === undefined || val === '') continue

      const type = Array.isArray(fieldSchema.type) ? fieldSchema.type[0] : fieldSchema.type

      if (type === 'number' || type === 'integer') {
        const num = Number(val)
        result[key] = isNaN(num) ? val : num
      } else if (type === 'boolean') {
        result[key] = val === true || val === 'true'
      } else if (type === 'array') {
        try {
          result[key] = typeof val === 'string' ? JSON.parse(val) : val
        } catch {
          result[key] = val
        }
      } else if (type === 'object') {
        if (fieldSchema.properties) {
          result[key] = val
        } else {
          try {
            result[key] = typeof val === 'string' ? JSON.parse(val) : val
          } catch {
            result[key] = val
          }
        }
      } else {
        result[key] = val
      }
    }
    return result
  }

  private resolveDefault(fieldSchema: JsonSchemaObject): unknown {
    if (fieldSchema.default !== undefined) return fieldSchema.default
    const type = Array.isArray(fieldSchema.type) ? fieldSchema.type[0] : fieldSchema.type
    if (type === 'boolean') return false
    return null
  }

  private extractInitialValue(
    value: Record<string, unknown> | null,
    key: string,
    fieldSchema: JsonSchemaObject,
    fallback: unknown
  ): unknown {
    if (!value || !(key in value)) return fallback
    const raw = value[key]
    if (raw === null || raw === undefined) return fallback

    const type = Array.isArray(fieldSchema.type) ? fieldSchema.type[0] : fieldSchema.type
    if (type === 'array' || (type === 'object' && !fieldSchema.properties)) {
      return typeof raw === 'string' ? raw : JSON.stringify(raw, null, 2)
    }
    return raw
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

  protected getFieldErrors(key: string): string[] {
    return this.fieldErrors().get(key) ?? []
  }
}
