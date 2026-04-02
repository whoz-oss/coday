import { ChangeDetectionStrategy, Component, forwardRef, input } from '@angular/core'
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
export class JsonSchemaFieldComponent {
  readonly fieldKey = input.required<string>()
  readonly fieldSchema = input.required<JsonSchemaObject>()
  readonly control = input.required<FormControl>()
  readonly required = input<boolean>(false)

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
        // Render as nested form only if the schema has declared properties
        return schema.properties ? 'nested-object' : 'textarea'
      case 'array':
        return 'textarea'
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
    this.control().setValue(value, { emitEvent: true })
  }

  /** Extract current nested object value for the nested form's [value] input */
  protected get nestedValue(): Record<string, unknown> | null {
    const v = this.control().value
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
  }
}
