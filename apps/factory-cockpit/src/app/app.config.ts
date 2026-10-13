import {
  ApplicationConfig,
  LOCALE_ID,
  inject,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core'
import { provideHttpClient } from '@angular/common/http'
import { provideRouter, withComponentInputBinding } from '@angular/router'
import { MatIconRegistry } from '@angular/material/icon'
import { MatPaginatorIntl } from '@angular/material/paginator'
import { FrPaginatorIntl } from './core/paginator-intl.fr'
import { registerLocaleData } from '@angular/common'
import localeEn from '@angular/common/locales/en'
import { routes } from './app.routes'
import { AGENTOS_BASE_URL } from './core/factory.store'
import { environment } from '../environments/environment'

registerLocaleData(localeEn)

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(),
    { provide: LOCALE_ID, useValue: 'en' },
    { provide: MatPaginatorIntl, useClass: FrPaginatorIntl },
    provideRouter(routes, withComponentInputBinding()),
    // AgentOS UI origin, resolved at build time via environment file replacement:
    // - development : 'http://localhost:4200' (cockpit :4300, AgentOS UI :4200)
    // - production  : ''                      (same-origin gateway)
    { provide: AGENTOS_BASE_URL, useValue: environment.agentOsBaseUrl },
    // <mat-icon>name</mat-icon> uses Material Symbols Outlined by default
    provideAppInitializer(() => {
      inject(MatIconRegistry).setDefaultFontSetClass('material-symbols-outlined')
    }),
  ],
}
