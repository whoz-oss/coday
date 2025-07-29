import { Component } from '@angular/core'
import { RouterModule } from '@angular/router'
import { FloatingMenuComponent } from './components/floating-menu/floating-menu.component'

@Component({
  imports: [RouterModule, FloatingMenuComponent],
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  protected title = 'web-ng'
}
