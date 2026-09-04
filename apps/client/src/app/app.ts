import { Component, inject, OnInit, ChangeDetectionStrategy } from '@angular/core'
import { RouterModule } from '@angular/router'
import { ThemeService } from './core/services/theme.service'

@Component({
  imports: [RouterModule],
  selector: 'app-root',
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.Eager,
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
