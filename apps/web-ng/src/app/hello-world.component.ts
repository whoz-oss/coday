import { Component } from '@angular/core'

@Component({
  selector: 'app-hello-world',
  standalone: true,
  template: `
    <div class="hello-container">
      <h1>🎯 Coday Angular Migration</h1>
      <p>Hello World from Angular! 🚀</p>
      <p><strong>Issue #147 - Phase 1 Complete ✅</strong></p>
      
      <div class="status">
        <h3>Current Status:</h3>
        <ul>
          <li>✅ Angular app created in Nx workspace (apps/web-ng)</li>
          <li>✅ Standalone components architecture</li>
          <li>✅ Ready for component migration</li>
          <li>🔄 Next: Configure routing to /ng</li>
        </ul>
      </div>

      <div class="info">
        <h3>Technical Details:</h3>
        <ul>
          <li><strong>Framework:</strong> Angular with standalone components</li>
          <li><strong>Build Tool:</strong> esbuild via Nx</li>
          <li><strong>Styling:</strong> SCSS</li>
          <li><strong>Testing:</strong> Jest + Playwright</li>
          <li><strong>Location:</strong> apps/web-ng (Nx standard)</li>
        </ul>
      </div>
    </div>
  `,
  styles: [`
    .hello-container {
      padding: 2rem;
      max-width: 800px;
      margin: 0 auto;
      font-family: system-ui, -apple-system, sans-serif;
      line-height: 1.6;
    }

    h1 {
      color: #2563eb;
      text-align: center;
      margin-bottom: 1rem;
    }

    p {
      text-align: center;
      font-size: 1.1rem;
      margin-bottom: 1rem;
    }

    .status, .info {
      margin: 2rem 0;
      padding: 1.5rem;
      border-radius: 8px;
      background: #f8fafc;
      border: 1px solid #e2e8f0;
    }

    .status {
      background: #f0f9ff;
      border-color: #0ea5e9;
    }

    .info {
      background: #fefce8;
      border-color: #eab308;
    }

    h3 {
      margin-top: 0;
      margin-bottom: 1rem;
      color: #1e293b;
    }

    ul {
      list-style: none;
      padding: 0;
    }

    li {
      padding: 0.5rem 0;
      border-bottom: 1px solid #e2e8f0;
    }

    li:last-child {
      border-bottom: none;
    }

    li strong {
      color: #1e293b;
    }
  `]
})
export class HelloWorldComponent { }