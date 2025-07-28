import { Component } from '@angular/core'
import { CommonModule } from '@angular/common'
import { MainAppComponent } from './components/main-app/main-app.component'

@Component({
  selector: 'app-hello-world',
  standalone: true,
  imports: [CommonModule, MainAppComponent],
  template: `
    <div class="dev-container">
      <div class="dev-header">
        <h1>🎯 Coday Angular - Phase 3</h1>
        <p>Services Connected 🚀</p>
        <div class="status">
          ✅ Components created<br>
          ✅ Services integrated<br>
          🔄 Ready for real Coday connection
        </div>
      </div>
      
      <!-- Main App Component -->
      <div class="app-container">
        <app-main></app-main>
      </div>
    </div>
  `,
  styles: [`
    .dev-container {
      height: 100vh;
      display: flex;
      flex-direction: column;
      font-family: system-ui, sans-serif;
    }

    .dev-header {
      padding: 1rem;
      background: #f8fafc;
      border-bottom: 1px solid #e5e7eb;
      text-align: center;
    }

    .dev-header h1 {
      margin: 0 0 0.5rem 0;
      color: #1e293b;
    }

    .dev-header p {
      margin: 0 0 1rem 0;
      color: #64748b;
    }

    .status {
      background: #f0f9ff;
      border: 1px solid #0ea5e9;
      border-radius: 4px;
      padding: 0.75rem;
      font-size: 0.9rem;
      line-height: 1.4;
      max-width: 300px;
      margin: 0 auto;
    }

    .app-container {
      flex: 1;
      min-height: 0;
    }
  `]
})
export class HelloWorldComponent {
  // Clean component - just wraps the main app
}