# TextInputComponent

Generic text input component with label and hint support. Designed to be reusable across the application for simple text input needs.

## Features

- Label support
- Placeholder text
- Hint text (small text below input)
- Auto-focus capability
- Enter key handling
- Disabled state
- Programmatic focus method

## Usage

```typescript
import { TextInputComponent } from './text-input/text-input.component'

@Component({
  // ...
  imports: [TextInputComponent]
})
```

```html
<app-text-input
  label="Project Name"
  placeholder="my-project"
  hint="Enter a unique project name"
  [value]="projectName"
  (valueChange)="projectName = $event"
  (enterPressed)="onSubmit()"
  [isDisabled]="false"
  [autoFocus]="true"
></app-text-input>
```

## Inputs

- `label: string` - Label text displayed above the input
- `placeholder: string` - Placeholder text inside the input
- `hint: string` - Hint text displayed below the input (small, italic)
- `value: string` - Initial value for the input
- `isDisabled: boolean` - Whether the input is disabled
- `autoFocus: boolean` - Whether to auto-focus on mount (default: true)

## Outputs

- `valueChange: EventEmitter<string>` - Emits on every input change
- `enterPressed: EventEmitter<string>` - Emits when Enter key is pressed (with trimmed value)

## Public Methods

- `focus(): void` - Programmatically focus the input field

## Styling

The component uses CSS variables for theming:
- `--color-text` - Text color
- `--color-text-secondary` - Hint text color
- `--color-bg` - Input background
- `--color-border` - Input border
- `--color-primary` - Focus border color
- `--color-surface` - Disabled background

## Example: Sequential Inputs

```typescript
@ViewChild('nameInput') nameInput!: TextInputComponent
@ViewChild('emailInput') emailInput!: TextInputComponent

onNameEnter(): void {
  // Move focus to next input
  this.emailInput.focus()
}
```

```html
<app-text-input
  #nameInput
  label="Name"
  (enterPressed)="onNameEnter()"
></app-text-input>

<app-text-input
  #emailInput
  label="Email"
  [autoFocus]="false"
  (enterPressed)="onSubmit()"
></app-text-input>
```
