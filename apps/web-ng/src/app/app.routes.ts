import { Route } from '@angular/router'
import { HelloWorldComponent } from './hello-world.component'

export const appRoutes: Route[] = [
  { path: '', component: HelloWorldComponent },
  { path: '**', redirectTo: '' }
]
