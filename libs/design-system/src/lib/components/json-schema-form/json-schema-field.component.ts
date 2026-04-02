import { ChangeDetectionStrategy, Component, OnInit, forwardRef, input, signal } from '@angular/core'
import { FormControl, ReactiveFormsModule } from '@angular/forms'
import { JsonSchemaFormComponent } from './json-schema-form.component'
import { JsonSchemaObject } from './json-schema.model'

/**
 * ds-json-schema-field — renders a single form field from a JSON Schema property.
 *
 * Handles: string, number, integer, boolean, enum, object (with nested ds-json-schema-form),
 * array (textarea fallback).
 *
 * Nested objects that have `properties` are rendered recursively by embedding
 * another ds-json-schema-form. Objects without `properties` fall back to a
 * JSON textarea.
 *
 * String fields respect the `format` keyword to pick the right HTML input type:
 *   - password  → <input type="password">
 *   - email     → <input type="email">
 *   - uri / url → <input type="url">
 *   - date      → <input type="date">
 *   - date-time → <input type="datetime-local">
 *   - (others)  → <input type="text">
 *
 * This is an internal component used only by ds-json-schema-form.
 * It is NOT exported from the design-system public API.
 */
@Component({
  selector: 'ds-json-schema-field',
  standalone: true,
  imports: [ReactiveFormsModule, forwardRef(() => JsonSchemaFormComponent)],
  templateUrl: './json-schema-field.component.html',
  styleUrl: './json-schema-field.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JsonSchemaFieldComponent implements OnInit {
  readonly fieldKey = input.required<string>()
  readonly fieldSchema = input.required<JsonSchemaObject>()
  readonly control = input.required<FormControl>()
  readonly required = input<boolean>(false)
  /** Validation error messages from Ajv, passed down by ds-json-schema-form. */
  readonly errors = input<string[]>([])

  /**
   * Initial value seed for the nested ds-json-schema-form.
   * Set once in ngOnInit — signal inputs are not available during field
   * property initialisation (NG0950).
   * The nested form manages its own state after seeding; a live binding
   * would cause infinite change-detection loops.
   */
  protected readonly nestedInitialValue = signal<Record<string, unknown> | null>(null)

  ngOnInit(): void {
    const v = this.control().value
    this.nestedInitialValue.set(v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null)
  }

  protected get label(): string {
    return this.fieldSchema().title ?? this.fieldKey()
  }

  protected get description(): string | undefined {
    return this.fieldSchema().description
  }

  protected get fieldType(): 'text' | 'number' | 'boolean' | 'enum' | 'nested-object' | 'textarea' {
    const schema = this.fieldSchema()
    if (schema.enum?.length) return 'enum'
    const type = Array.isArray(schema.type) ? schema.type[0] : schema.type
    switch (type) {
      case 'number':
      case 'integer':
        return 'number'
      case 'boolean':
        return 'boolean'
      case 'object':
        return schema.properties ? 'nested-object' : 'textarea'
      case 'array':
        return 'textarea'
      default:
        return 'text'
    }
  }

  /**
   * HTML input `type` attribute for text fields.
   * Maps JSON Schema `format` keywords to their HTML equivalents.
   * Falls back to `"text"` for any unknown format.
   */
  protected get htmlInputType(): string {
    const format = this.fieldSchema().format as string | undefined
    switch (format) {
      case 'password':
        return 'password'
      case 'email':
        return 'email'
      case 'uri':
      case 'url':
        return 'url'
      case 'date':
        return 'date'
      case 'date-time':
        return 'datetime-local'
      default:
        return 'text'
    }
  }

  protected get enumOptions(): string[] {
    return (this.fieldSchema().enum as string[]) ?? []
  }

  protected get inputId(): string {
    return `ds-field-${this.fieldKey()}`
  }

  /** Called by the nested JsonSchemaFormComponent when its value changes */
  protected onNestedValueChange(value: Record<string, unknown> | null): void {
    // Only update if the value actually changed to avoid infinite loops.
    const current = this.control().value
    if (JSON.stringify(current) !== JSON.stringify(value)) {
      this.control().setValue(value, { emitEvent: true })
    }
  }
}
