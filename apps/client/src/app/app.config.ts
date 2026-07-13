import {
  APP_INITIALIZER,
  ApplicationConfig,
  Injector,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core'
import { firstValueFrom, of } from 'rxjs'
import { catchError } from 'rxjs/operators'
import { provideRouter } from '@angular/router'
import { provideHttpClient } from '@angular/common/http'
import { provideAnimations } from '@angular/platform-browser/animations'
import { MAT_DIALOG_DEFAULT_OPTIONS } from '@angular/material/dialog'
import { MAT_SNACK_BAR_DEFAULT_OPTIONS } from '@angular/material/snack-bar'
import { provideApi } from '@whoz-oss/agentos-api-client'
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

/**
 * Load the current user once at app startup so that UserStateService.currentUser() is
 * populated before any AgentOS component mounts. Without this, NamespaceRoleStateService
 * receives null from currentUser$ and caches a stale `false` for admin checks, causing
 * edit/delete buttons to remain hidden on namespace config pages.
 *
 * UserStateService lives in agentos-ui which is lazy-loaded, so we cannot import it
 * statically here. We use a dynamic import inside the factory instead — Angular's DI
 * resolves the service at runtime regardless, because it is providedIn: 'root'.
 *
 * Errors are caught and logged — an unauthenticated or anonymous session should not block
 * the app from rendering; components that need the user will degrade gracefully.
 */
function initializeCurrentUser(injector: Injector) {
  return async () => {
    const { UserStateService } = await import('@whoz-oss/agentos-ui')
    const userState = injector.get(UserStateService)
    await firstValueFrom(
      userState.loadMe().pipe(
        catchError((err) => {
          console.warn('Failed to load current user', err)
          return of(null)
        })
      )
    )
  }
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(appRoutes),
    provideHttpClient(),
    provideAnimations(),
    // MAT_SNACK_BAR_DEFAULT_OPTIONS registers the snackbar token without pulling
    // the full MatSnackBarModule into the root bundle — components that use
    // MatSnackBar will still get it via their own imports.
    { provide: MAT_SNACK_BAR_DEFAULT_OPTIONS, useValue: { duration: 3000 } },
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
      provide: APP_INITIALIZER,
      useFactory: initializeCurrentUser,
      deps: [Injector],
      multi: true,
    },
    provideApi({ basePath: '/api/agentos' }),
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
