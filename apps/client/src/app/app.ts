import { Component, ChangeDetectionStrategy } from '@angular/core'
import { RouterModule } from '@angular/router'

@Component({
  imports: [RouterModule],
  selector: 'app-root',
  templateUrl: './app.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './app.scss',
})
export class App {
  protected title = 'client'
}
