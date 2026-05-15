import { bootstrapApplication } from '@angular/platform-browser'
import { isDevMode } from '@angular/core'
import { appConfig } from './app/app.config'
import { App } from './app/app'

bootstrapApplication(App, appConfig).catch((err) => console.error(err))

if ('serviceWorker' in navigator && !isDevMode()) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch((err) => console.error('[SW] Registration failed:', err))
  })
}
