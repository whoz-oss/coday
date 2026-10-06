import { Injectable, signal } from '@angular/core'

export interface Crumb {
  label: string
  link?: string
  mono?: boolean
}

/** Fil d'Ariane affiché dans la barre du haut ; chaque page le renseigne. */
@Injectable({ providedIn: 'root' })
export class ShellState {
  readonly crumbs = signal<Crumb[]>([])
}
