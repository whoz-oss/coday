import { Component, inject, OnInit } from '@angular/core'
import { RouterModule } from '@angular/router'
import { ThemeService } from './core/services/theme.service'

@Component({
  imports: [RouterModule],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  protected title = 'client'

  private themeService = inject(ThemeService)

  ngOnInit(): void {
    // S'assurer que le thème est appliqué dès le démarrage
    console.log('[APP] Application initialized with theme:', this.themeService.getCurrentTheme())
  }
}
