import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
  APP_INITIALIZER,
} from '@angular/core'
import { provideRouter } from '@angular/router'
import { provideHttpClient } from '@angular/common/http'
import { provideAnimations } from '@angular/platform-browser/animations'
import { MatSnackBarModule } from '@angular/material/snack-bar'
import { importProvidersFrom } from '@angular/core'
import { appRoutes } from './app.routes'
import { ProjectStateService } from './core/services/project-state.service'

/**
 * Initialize default project selection at app startup
 */
function initializeDefaultProject(projectState: ProjectStateService) {
  return () => projectState.initializeDefaultProject()
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(appRoutes),
    provideHttpClient(),
    provideAnimations(),
    importProvidersFrom(MatSnackBarModule),
    {
      provide: APP_INITIALIZER,
      useFactory: initializeDefaultProject,
      deps: [ProjectStateService],
      multi: true,
    },
  ],
}
