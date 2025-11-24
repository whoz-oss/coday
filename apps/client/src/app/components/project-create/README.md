# ProjectCreateComponent

Component for creating a new project. Provides a form with project name and path inputs using the reusable TextInputComponent.

## Features

- Two-field form (name and path)
- Sequential focus flow (Enter on name → focus path)
- Validation (both fields required)
- Loading state during creation
- Error message display
- Cancel functionality

## Usage

```typescript
import { ProjectCreateComponent } from './project-create/project-create.component'

@Component({
  // ...
  imports: [ProjectCreateComponent]
})
```

```html
<app-project-create
  [isCreating]="isCreating"
  (create)="onCreateProject($event)"
  (cancel)="onCancel()"
></app-project-create>
```

## Inputs

- `isCreating: boolean` - Whether the creation is in progress (disables inputs and shows "Creating..." text)

## Outputs

- `create: EventEmitter<{ name: string; path: string }>` - Emits when user clicks Create button with valid data
- `cancel: EventEmitter<void>` - Emits when user clicks Cancel button

## Behavior

### Sequential Input Flow
1. Name input is auto-focused on mount
2. Pressing Enter on name field focuses path field (if name is valid)
3. Pressing Enter on path field submits the form (if both fields are valid)

### Validation
- Both name and path must be non-empty (after trimming)
- Create button is disabled if validation fails
- Error message shown if user tries to submit invalid data

### Creation Flow
```typescript
onCreateProject(data: { name: string; path: string }): void {
  this.isCreating = true
  
  this.projectService.createProject(data.name, data.path).subscribe({
    next: (response) => {
      this.isCreating = false
      // Handle success (close form, navigate, etc.)
    },
    error: (err) => {
      this.isCreating = false
      // Handle error
    }
  })
}
```

## Styling

The component includes:
- Slide-in animation on mount
- Responsive design (mobile-friendly)
- Primary action button (Create) with hover effects
- Secondary action button (Cancel) with subtle styling
- Error message styling with red accent

## Integration Example

See `ProjectSelectionComponent` for a complete integration example showing:
- Conditional display of creation form
- API call handling
- Auto-selection of newly created project
- Error handling and user feedback
