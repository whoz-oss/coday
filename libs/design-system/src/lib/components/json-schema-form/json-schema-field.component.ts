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
 * array of scalars with `items.enum` (checkbox group — see multi-enum below),
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
 * String fields with `"x-ui-widget": "textarea"` render a resizable <textarea>
 * regardless of `format`. Use this for fields expected to hold long text
 * (prompts, templates, multi-line configs, etc.).
 *
 * Array-of-scalars: each item is a typed <input> with an × remove button.
 *   When the schema sets `uniqueItems: true`, duplicate values are flagged
 *   visually (border highlight) rather than hard-blocked.
 * Multi-enum (array whose `items.enum` is non-empty): rendered as a checkbox
 *   group, one checkbox per enum value — a set is structurally unique and
 *   shows the full domain upfront, which is more honest than a repeated
 *   <select> list for this case. The FormControl value stays an array of the
 *   checked values, always ordered per the enum's declaration order (not
 *   click order) so the produced value is deterministic. A value present in
 *   the initial data but absent from the enum is silently dropped — this
 *   keeps initialisation simple and is considered acceptable since such a
 *   value could not be re-selected through this widget anyway.
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
    } else if (this.fieldType === 'multi-enum') {
      this.initMultiEnum()
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

  /** Whether the schema requests uniqueness for 'array-scalar' items. */
  protected get uniqueItemsEnabled(): boolean {
    return this.fieldSchema().uniqueItems === true
  }

  /**
   * Cosmetic uniqueness check for 'array-scalar' items: flags an item whose
   * current value is duplicated elsewhere in the list. Does not block input —
   * a lightweight visual nudge, not a full validation layer.
   */
  protected isDuplicateScalarItem(index: number): boolean {
    if (!this.uniqueItemsEnabled) return false
    const value = this.itemArray.at(index)?.value
    if (value === null || value === undefined || value === '') return false
    return this.itemArray.controls.filter((c) => c.value === value).length > 1
  }

  // ---------------------------------------------------------------------------
  // Multi-enum (array with items.enum) helpers
  // ---------------------------------------------------------------------------

  /**
   * Currently checked values for the 'multi-enum' field type.
   * Kept as a Set for cheap membership checks; the emitted control value is
   * always derived from `multiEnumOptions` order, never from this Set's
   * iteration order.
   */
  protected readonly selectedMultiEnum = signal<ReadonlySet<unknown>>(new Set())

  /** The enum values declared on `items.enum`, in schema declaration order. */
  protected get multiEnumOptions(): unknown[] {
    return this.fieldSchema().items?.enum ?? []
  }

  private initMultiEnum(): void {
    const options = this.multiEnumOptions
    const existing = this.control().value
    const source: unknown[] = Array.isArray(existing) ? existing : []
    // Values absent from the enum are silently ignored for checkbox state (see
    // class JSDoc), but we deliberately do NOT rewrite the control's value here
    // — any unknown entries already present in `existing` are left untouched
    // until the user next toggles a checkbox, at which point syncMultiEnumToControl
    // recomputes the value from scratch (and drops them). This mirrors
    // initScalarArray/initObjectArray, which also never call back into the
    // parent control during initialisation.
    this.selectedMultiEnum.set(new Set(source.filter((v) => options.includes(v))))
  }

  protected isMultiEnumChecked(option: unknown): boolean {
    return this.selectedMultiEnum().has(option)
  }

  protected toggleMultiEnumOption(option: unknown, checked: boolean): void {
    const next = new Set(this.selectedMultiEnum())
    if (checked) {
      next.add(option)
    } else {
      next.delete(option)
    }
    this.selectedMultiEnum.set(next)
    this.syncMultiEnumToControl(next)
  }

  /** Emits the selection to the parent FormControl, ordered per enum declaration order. */
  private syncMultiEnumToControl(selected: ReadonlySet<unknown>): void {
    const ordered = this.multiEnumOptions.filter((option) => selected.has(option))
    this.control().setValue(ordered, { emitEvent: true })
  }

  protected multiEnumOptionId(index: number): string {
    return `${this.inputId}-${index}`
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
    | 'multi-enum'
    | 'nested-object'
    | 'array-scalar'
    | 'array-object'
    | 'map'
    | 'textarea' {
    const schema = this.fieldSchema()
    if (schema.enum?.length) return 'enum'
    // x-ui-widget extension: explicit widget override takes priority over type inference.
    if (schema['x-ui-widget'] === 'textarea') return 'textarea'
    const type = Array.isArray(schema.type) ? schema.type[0] : schema.type
    switch (type) {
      case 'number':
      case 'integer':
        return 'number'
      case 'boolean':
        return 'boolean'
      case 'array': {
        const itemsSchema = schema.items
        if (itemsSchema?.enum?.length) return 'multi-enum'
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
