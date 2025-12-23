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
import { OAuthService } from './core/services/oauth.service'

/**
 * Initialize default project selection at app startup
 */
function initializeDefaultProject(projectState: ProjectStateService) {
  return () => projectState.initializeDefaultProject()
}

/**
 * Initialize OAuth service at app startup
 * This ensures the service subscribes to OAuth events even if not directly used by components
 */
function initializeOAuthService(_oauthService: OAuthService) {
  return () => {
    // The underscore prefix indicates intentional non-usage
    // Simply injecting the service is enough to initialize its subscriptions
    // The service constructor subscribes to events automatically
    console.log('[APP_INIT] OAuthService initialized')
  }
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
      provide: APP_INITIALIZER,
      useFactory: initializeOAuthService,
      deps: [OAuthService],
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
