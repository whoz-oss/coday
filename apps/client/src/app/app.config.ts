import {
  APP_INITIALIZER,
  ApplicationConfig,
  importProvidersFrom,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core'
import { provideRouter } from '@angular/router'
import { provideHttpClient } from '@angular/common/http'
import { provideAnimations } from '@angular/platform-browser/animations'
import { MatSnackBarModule } from '@angular/material/snack-bar'
import { MAT_DIALOG_DEFAULT_OPTIONS } from '@angular/material/dialog'
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
    {
      provide: MAT_DIALOG_DEFAULT_OPTIONS,
      useValue: {
        width: '80vw',
        maxWidth: '80vw',
        maxHeight: '80vh',
      },
    },
  ],
}
