import { ChangeDetectionStrategy, Component, OnInit, forwardRef, input, signal } from '@angular/core'
import { FormArray, FormControl, ReactiveFormsModule } from '@angular/forms'
import { JsonSchemaFormComponent } from './json-schema-form.component'
import { JsonSchemaObject } from './json-schema.model'

/** A single row in a map (key-value) field. */
interface MapEntry {
  /** Stable uid for @for tracking — never changes after creation. */
  uid: number
  key: string
  value: string
}

/** Wrapper giving each array-of-objects item a stable identity for @for tracking. */
interface ObjectItem {
  /** Stable unique id — never changes after creation. Used for @for track. */
  uid: number
  /** Seed passed once to the child ds-json-schema-form as [value]. Never updated
   *  after creation to avoid re-seeding the child form and triggering an infinite loop. */
  seed: Record<string, unknown> | null
  /** Current value emitted by the child ds-json-schema-form. */
  value: Record<string, unknown> | null
}

let nextUid = 0
function makeItem(seed: Record<string, unknown> | null): ObjectItem {
  return { uid: nextUid++, seed, value: seed }
}

/**
 * ds-json-schema-field — renders a single form field from a JSON Schema property.
 *
 * Handles: string, number, integer, boolean, enum, object (nested ds-json-schema-form),
 * array of scalars (typed item inputs with add/remove),
 * array of objects (each item = nested ds-json-schema-form with add/remove),
 * raw object (JSON textarea fallback).
 *
 * String fields respect the `format` keyword to pick the right HTML input type:
 *   - password  → <input type="password">
 *   - email     → <input type="email">
 *   - uri / url → <input type="url">
 *   - date      → <input type="date">
 *   - date-time → <input type="datetime-local">
 *   - (others)  → <input type="text">
 *
 * Array-of-scalars: each item is a typed <input> with an × remove button.
 * Array-of-objects: each item is rendered as a ds-json-schema-form card with
 *   an × remove button. Items are tracked by a stable uid so Angular destroys
 *   the correct DOM node on removal.
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

  // ---------------------------------------------------------------------------
  // Array-of-scalars state
  // ---------------------------------------------------------------------------

  /**
   * Internal FormArray used only when fieldType === 'array-scalar'.
   * The parent FormControl holds the plain array value; this FormArray
   * drives the rendered list of item inputs and syncs back on every change.
   */
  protected readonly itemArray = new FormArray<FormControl<unknown>>([])

  // ---------------------------------------------------------------------------
  // Map (key-value) state
  // ---------------------------------------------------------------------------

  /**
   * Internal row list for the 'map' field type.
   * Each entry has a stable uid (for @for tracking), a key string and a value string.
   * The parent FormControl is kept in sync as a plain Record<string, string>.
   */
  protected readonly mapEntries = signal<MapEntry[]>([])

  // ---------------------------------------------------------------------------
  // Array-of-objects state
  // ---------------------------------------------------------------------------

  /**
   * Items for an array-of-objects field. Each entry carries a stable `uid`
   * (used for @for tracking so Angular destroys the correct DOM node on
   * removal), an immutable `seed` (initial value for the child form), and
   * the current `value` emitted by the child form.
   */
  protected readonly objectItems = signal<ObjectItem[]>([])

  // ---------------------------------------------------------------------------
  // Nested-object state
  // ---------------------------------------------------------------------------

  /**
   * Initial value seed for the nested ds-json-schema-form (nested-object case).
   * Set once in ngOnInit — signal inputs are not available during field
   * property initialisation (NG0950).
   */
  protected readonly nestedInitialValue = signal<Record<string, unknown> | null>(null)

  ngOnInit(): void {
    if (this.fieldType === 'array-scalar') {
      this.initScalarArray()
    } else if (this.fieldType === 'array-object') {
      this.initObjectArray()
    } else if (this.fieldType === 'map') {
      this.initMapEntries()
    } else if (this.fieldType === 'nested-object') {
      const v = this.control().value
      this.nestedInitialValue.set(
        v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Map (key-value) helpers
  // ---------------------------------------------------------------------------

  private initMapEntries(): void {
    const existing = this.control().value
    const entries: MapEntry[] =
      existing && typeof existing === 'object' && !Array.isArray(existing)
        ? Object.entries(existing as Record<string, unknown>).map(([k, v]) => ({
            uid: nextUid++,
            key: k,
            value: String(v ?? ''),
          }))
        : []
    this.mapEntries.set(entries)
  }

  protected addMapEntry(): void {
    this.mapEntries.update((entries) => [...entries, { uid: nextUid++, key: '', value: '' }])
    // No sync needed — an empty key is excluded from the emitted object.
  }

  protected removeMapEntry(uid: number): void {
    this.mapEntries.update((entries) => entries.filter((e) => e.uid !== uid))
    this.syncMapToControl()
  }

  protected onMapKeyChange(uid: number, event: Event): void {
    const key = (event.target as HTMLInputElement).value
    this.mapEntries.update((entries) => entries.map((e) => (e.uid === uid ? { ...e, key } : e)))
    this.syncMapToControl()
  }

  protected onMapValueChange(uid: number, event: Event): void {
    const value = (event.target as HTMLInputElement).value
    this.mapEntries.update((entries) => entries.map((e) => (e.uid === uid ? { ...e, value } : e)))
    this.syncMapToControl()
  }

  private syncMapToControl(): void {
    const obj: Record<string, string> = {}
    for (const entry of this.mapEntries()) {
      if (entry.key !== '') {
        obj[entry.key] = entry.value
      }
    }
    this.control().setValue(obj, { emitEvent: true })
  }

  // ---------------------------------------------------------------------------
  // Array-of-scalars helpers
  // ---------------------------------------------------------------------------

  private initScalarArray(): void {
    const existing = this.control().value
    const items: unknown[] = Array.isArray(existing) ? existing : []
    items.forEach((item) => this.itemArray.push(new FormControl<unknown>(item)))

    this.itemArray.valueChanges.subscribe((values) => {
      this.control().setValue(values, { emitEvent: true })
    })
  }

  protected addScalarItem(): void {
    this.itemArray.push(new FormControl<unknown>(null))
  }

  protected removeScalarItem(index: number): void {
    this.itemArray.removeAt(index)
  }

  protected getScalarItemControl(index: number): FormControl<unknown> {
    return this.itemArray.at(index) as FormControl<unknown>
  }

  /** HTML input type for scalar array items, derived from `items.type`. */
  protected get itemInputType(): string {
    const itemsSchema = this.fieldSchema().items
    if (!itemsSchema) return 'text'
    const type = Array.isArray(itemsSchema.type) ? itemsSchema.type[0] : itemsSchema.type
    return type === 'number' || type === 'integer' ? 'number' : 'text'
  }

  // ---------------------------------------------------------------------------
  // Array-of-objects helpers
  // ---------------------------------------------------------------------------

  private initObjectArray(): void {
    const existing = this.control().value
    const items: Array<Record<string, unknown>> = Array.isArray(existing) ? existing : []
    this.objectItems.set(items.map((item) => makeItem({ ...item })))
  }

  protected addObjectItem(): void {
    this.objectItems.update((items) => [...items, makeItem(null)])
    this.syncObjectArrayToControl()
  }

  protected removeObjectItem(uid: number): void {
    this.objectItems.update((items) => items.filter((item) => item.uid !== uid))
    this.syncObjectArrayToControl()
  }

  protected onObjectItemChange(uid: number, value: Record<string, unknown> | null): void {
    this.objectItems.update((items) => items.map((item) => (item.uid === uid ? { ...item, value } : item)))
    this.syncObjectArrayToControl()
  }

  /** Schema for object items — the `items` sub-schema. */
  protected get itemObjectSchema(): JsonSchemaObject | null {
    return this.fieldSchema().items ?? null
  }

  private syncObjectArrayToControl(): void {
    this.control().setValue(
      this.objectItems().map((item) => item.value),
      { emitEvent: true }
    )
  }

  // ---------------------------------------------------------------------------
  // Field metadata
  // ---------------------------------------------------------------------------

  protected get label(): string {
    return this.fieldSchema().title ?? this.fieldKey()
  }

  protected get description(): string | undefined {
    return this.fieldSchema().description
  }

  protected get fieldType():
    | 'text'
    | 'number'
    | 'boolean'
    | 'enum'
    | 'nested-object'
    | 'array-scalar'
    | 'array-object'
    | 'map'
    | 'textarea' {
    const schema = this.fieldSchema()
    if (schema.enum?.length) return 'enum'
    const type = Array.isArray(schema.type) ? schema.type[0] : schema.type
    switch (type) {
      case 'number':
      case 'integer':
        return 'number'
      case 'boolean':
        return 'boolean'
      case 'array': {
        const itemsSchema = schema.items
        const itemType = itemsSchema
          ? Array.isArray(itemsSchema.type)
            ? itemsSchema.type[0]
            : itemsSchema.type
          : undefined
        return itemType === 'object' && itemsSchema?.properties ? 'array-object' : 'array-scalar'
      }
      case 'object':
        if (schema.properties) return 'nested-object'
        if (schema.additionalProperties && schema.additionalProperties !== false) return 'map'
        return 'textarea'
      default:
        return 'text'
    }
  }

  /**
   * HTML input `type` attribute for text fields.
   * Maps JSON Schema `format` keywords to their HTML equivalents.
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

  /** Called by the nested JsonSchemaFormComponent (nested-object case) when its value changes. */
  protected onNestedValueChange(value: Record<string, unknown> | null): void {
    const current = this.control().value
    if (JSON.stringify(current) !== JSON.stringify(value)) {
      this.control().setValue(value, { emitEvent: true })
    }
  }
}
