import { Component, inject, OnInit } from '@angular/core'
import { RouterModule } from '@angular/router'
import { FloatingMenuComponent } from './components/floating-menu/floating-menu.component'
import { ThemeService } from './core/services/theme.service'

@Component({
  imports: [RouterModule, FloatingMenuComponent],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  protected title = 'web-ng'

  private themeService = inject(ThemeService)
  
  ngOnInit(): void {
    // S'assurer que le thème est appliqué dès le démarrage
    console.log('[APP] Application initialized with theme:', this.themeService.getCurrentTheme())
  }
}
