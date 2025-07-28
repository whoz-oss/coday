import { Component } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MainAppComponent } from './components/main-app/main-app.component'

@Component({
  selector: 'app-hello-world',
  standalone: true,
  imports: [CommonModule, MainAppComponent],
  template: `
    <app-main></app-main>
  `,
  styles: [``]
})
export class HelloWorldComponent {
  // Clean component - just wraps the main app
}