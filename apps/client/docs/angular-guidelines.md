# Angular Guidelines

Mandatory patterns for all code written in `apps/client`. No exceptions without explicit justification in a comment.

## Standalone Components

Every component is `standalone: true`. No NgModules. Imports are declared per-component.

```typescript
@Component({
  selector: 'app-my-component',
  standalone: true,
  imports: [NgClass, RouterModule, MyOtherComponent],
  templateUrl: './my-component.html',
  styleUrl: './my-component.scss',
})
export class MyComponent {}
```

## Dependency Injection

Use `inject()` at field initialisation. Never constructor injection.

```typescript
// correct
export class MyComponent {
  private readonly threadState = inject(ThreadStateService)
}

// wrong
export class MyComponent {
  constructor(private threadState: ThreadStateService) {}
}
```

## Control Flow

Use block syntax. Never structural directives.

```html
<!-- correct -->
@if (isLoading()) {
  <app-spinner />
} @else {
  <app-content [data]="items()" />
}

@for (item of items(); track item.id) {
  <app-item [item]="item" />
}

@switch (status()) {
  @case ('active') { <span>Active</span> }
  @default { <span>Inactive</span> }
}

<!-- wrong -->
<app-spinner *ngIf="isLoading" />
<app-item *ngFor="let item of items; trackBy: trackById" />
```

## Reactive State

For **new** state introduced in components or services, prefer signals.
Existing `BehaviorSubject`-based state in services is not required to be migrated unless you are already touching that service.

```typescript
// new state
protected items = signal<Item[]>([])
protected isLoading = signal(false)
protected count = computed(() => this.items().length)

// existing pattern — do not break
isLoading$ = this.isLoadingSubject.asObservable()
```

## Typed Forms

Always provide explicit generic types.

```typescript
// correct
name = new FormControl<string>('', { nonNullable: true })
count = new FormControl<number | null>(null)

// wrong
name = new FormControl('')
```

## Subscriptions and Memory Leaks

Every `subscribe()` in a component must be cleaned up. Use `takeUntilDestroyed()` (Angular 16+).

```typescript
private readonly destroyRef = inject(DestroyRef)

ngOnInit() {
  this.events$.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(...)
}
```

`EventSource` and `BehaviorSubject` in services are completed in `ngOnDestroy`.

## Change Detection

Apply `changeDetection: ChangeDetectionStrategy.OnPush` to presentational (dumb) components. Container components that subscribe to observables may omit it, but should be refactored toward signals over time.

## Component Size

Hard limit: **~200 lines** per component file. Beyond that, decompose into child components or extract logic into a service.

## Anti-Patterns — Never Do

| Anti-pattern | Replacement |
|---|---|
| `*ngIf`, `*ngFor`, `[ngSwitch]` | `@if`, `@for`, `@switch` |
| Constructor injection | `inject()` |
| `::ng-deep` | Global styles in `styles.scss` |
| HTTP calls in components | API service → state service |
| Business logic in components | State service |
| `any` type without comment | Explicit type |
| `BehaviorSubject` for new state | `signal()` |
| Missing `track` in `@for` loops | `track item.id` |
