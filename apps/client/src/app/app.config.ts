import {
  APP_INITIALIZER,
  ApplicationConfig,
  Injector,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
  runInInjectionContext,
} from '@angular/core'
import { provideRouter } from '@angular/router'
import { provideHttpClient } from '@angular/common/http'
import { provideAnimations } from '@angular/platform-browser/animations'
import { MAT_DIALOG_DEFAULT_OPTIONS } from '@angular/material/dialog'
import { MAT_SNACK_BAR_DEFAULT_OPTIONS } from '@angular/material/snack-bar'
import { provideApi } from '@whoz-oss/agentos-api-client'
import { appRoutes } from './app.routes'
import { loadCurrentUser } from './load-current-user'

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
 * the app from rendering; components that need the user will degrade gracefully. The wait is also
 * bounded (see loadCurrentUser): this runs before AgentOS may be up, and a hanging request would
 * block the whole bootstrap.
 */
function initializeCurrentUser(injector: Injector) {
  return async () => {
    const { UserStateService } = await import('@whoz-oss/agentos-ui')
    await loadCurrentUser(injector.get(UserStateService))
  }
}

/**
 * Start the companion's background concerns (global SSE stream + cross-tab coordination)
 * once at app startup, in the main document context.
 *
 * Same dynamic-import constraint as initializeCurrentUser above: agentos-ui is
 * lazy-loaded, so importing provideCompanion statically here would pull the whole
 * library into the root bundle. Injecting the services by hand from the already-built
 * root injector achieves the same thing without that cost.
 *
 * Errors are caught and logged — notifications are an enhancement, and a failure to
 * open the stream must never block the app from rendering.
 */
function initializeCompanion(injector: Injector) {
  return async () => {
    try {
      const { CompanionStateService, GlobalStreamService, TabCoordinatorService } = await import('@whoz-oss/agentos-ui')
      runInInjectionContext(injector, () => {
        // TabCoordinatorService.start() creates an effect, hence the injection context.
        injector.get(TabCoordinatorService).start()
      })
      // The stream first, its consumers after: they subscribe, they do not connect.
      injector.get(GlobalStreamService).start()
      injector.get(CompanionStateService).startSse()
    } catch (err) {
      console.warn('Failed to start the companion notification stream', err)
    }
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
      useFactory: initializeCurrentUser,
      deps: [Injector],
      multi: true,
    },
    provideApi({ basePath: '/api/agentos' }),
    {
      provide: APP_INITIALIZER,
      useFactory: initializeCompanion,
      deps: [Injector],
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
