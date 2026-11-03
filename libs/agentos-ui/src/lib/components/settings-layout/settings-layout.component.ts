import { ChangeDetectionStrategy, Component, input } from '@angular/core'
import { Params, RouterLink, RouterLinkActive } from '@angular/router'

export type SettingsRouterCommand = string | number | null | undefined | Record<string, string | number>

export interface SettingsNavigationItem {
  readonly label: string
  readonly commands: readonly SettingsRouterCommand[]
  readonly queryParams?: Params
  readonly exact?: boolean
  readonly hidden?: boolean
}

export interface SettingsNavigationGroup {
  readonly label: string
  readonly items: readonly SettingsNavigationItem[]
}

@Component({
  selector: 'agentos-settings-layout',
  imports: [RouterLink, RouterLinkActive],
  templateUrl: './settings-layout.component.html',
  styleUrl: './settings-layout.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SettingsLayoutComponent {
  readonly title = input.required<string>()
  readonly mark = input.required<string>()
  readonly navigationLabel = input.required<string>()
  readonly groups = input.required<readonly SettingsNavigationGroup[]>()

  /**
   * On the mobile strip, keep the current page visible in the horizontally scrolling navigation.
   * `block: 'nearest'` never moves the page vertically; desktop (vertical sidebar) is left alone.
   */
  protected onActiveChange(active: boolean, link: HTMLElement): void {
    if (!active || !window.matchMedia?.('(max-width: 760px)').matches) return
    link.scrollIntoView?.({ inline: 'center', block: 'nearest' })
  }
}
