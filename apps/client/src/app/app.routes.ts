import { Route } from '@angular/router'
import { MainAppComponent } from './components/main-app/main-app.component'

export const appRoutes: Route[] = [
  { path: '', component: MainAppComponent },
  { path: '**', redirectTo: '' }
]
