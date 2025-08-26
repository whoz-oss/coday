import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core'
import { provideRouter } from '@angular/router'
import { provideHttpClient } from '@angular/common/http'
import { provideAnimations } from '@angular/platform-browser/animations'
import { appRoutes } from './app.routes'

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(appRoutes),
    provideHttpClient(),
    provideAnimations(),
  ],
}
