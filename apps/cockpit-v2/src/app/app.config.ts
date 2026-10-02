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
import localeFr from '@angular/common/locales/fr'
import { routes } from './app.routes'

registerLocaleData(localeFr)

export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideHttpClient(),
    { provide: LOCALE_ID, useValue: 'fr' },
    { provide: MatPaginatorIntl, useClass: FrPaginatorIntl },
    provideRouter(routes, withComponentInputBinding()),
    // <mat-icon>name</mat-icon> utilise Material Symbols Outlined par défaut
    provideAppInitializer(() => {
      inject(MatIconRegistry).setDefaultFontSetClass('material-symbols-outlined')
    }),
  ],
}
