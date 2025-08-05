import { Component, OnInit } from '@angular/core'
import { RouterModule } from '@angular/router'
import { FloatingMenuComponent } from './components/floating-menu/floating-menu.component'
import { ThemeService } from './core/services/theme.service'
import { PreferencesService } from './services/preferences.service'
import { VoiceSynthesisService } from './services/voice-synthesis.service'

@Component({
  imports: [RouterModule, FloatingMenuComponent],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App implements OnInit {
  protected title = 'web-ng'
  
  constructor(
    private themeService: ThemeService,
    private preferencesService: PreferencesService,
    private voiceSynthesisService: VoiceSynthesisService
  ) {
    // L'injection des services suffit pour les initialiser
    // Les constructeurs des services se chargent de l'initialisation
    console.log('[APP] Services initialized at startup')
    
    // Utilisation explicite pour éviter les warnings TypeScript
    console.log('[APP] PreferencesService ready:', !!this.preferencesService)
    console.log('[APP] VoiceSynthesisService ready:', !!this.voiceSynthesisService)
  }
  
  ngOnInit(): void {
    // S'assurer que le thème est appliqué dès le démarrage
    console.log('[APP] Application initialized with theme:', this.themeService.getCurrentTheme())
  }
}
