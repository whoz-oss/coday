import { Component } from '@angular/core'
import { RouterOutlet } from '@angular/router'
import { HeaderComponent } from '../header/header.component'

/**
 * LayoutComponent — default layout: header + routed content.
 *
 * Used by most routes. For pages needing a different structure
 * (full-screen chat, admin panel…), create a dedicated layout component
 * that reuses HeaderComponent as a primitive.
 */
@Component({
  selector: 'agentos-layout',
  standalone: true,
  imports: [RouterOutlet, HeaderComponent],
  templateUrl: './layout.component.html',
  styleUrl: './layout.component.scss',
})
export class LayoutComponent {}
