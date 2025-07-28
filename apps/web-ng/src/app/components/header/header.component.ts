import { Component } from '@angular/core'

@Component({
  selector: 'app-header',
  standalone: true,
  template: `
    <header class="header">
      <h1 id="header-title">{{ title }}</h1>
    </header>
  `,
  styles: [`
    .header {
      padding: 1rem;
      background: #f8fafc;
      border-bottom: 1px solid #e2e8f0;
    }
    
    h1 {
      margin: 0;
      font-size: 1.5rem;
      color: #1e293b;
    }
  `]
})
export class HeaderComponent {
  title = 'Coday'

  // TODO: Connect to EventStreamService to handle ProjectSelectedEvent
  updateTitle(projectName: string | null) {
    this.title = projectName || 'Coday'
    document.title = this.title
  }
}