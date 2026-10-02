import { bootstrapApplication } from '@angular/platform-browser'
import { appConfig } from './app/app.config'
import { CockpitV2Component } from './app/cockpit-v2.component'

bootstrapApplication(CockpitV2Component, appConfig).catch((err) => console.error(err))
