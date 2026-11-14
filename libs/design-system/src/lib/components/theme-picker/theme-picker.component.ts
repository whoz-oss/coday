import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core'
import { MatIcon } from '@angular/material/icon'
import { DS_THEME_VARIANTS, DsThemeService, ThemeMode, isThemeVariant, nextThemeMode } from '../../theme/theme.service'

const MODE_ICONS: Record<ThemeMode, string> = { light: 'light_mode', dark: 'dark_mode', system: 'brightness_auto' }

/**
 * DsThemePicker — the skin (Industry / Terminal / Whoz OSS) and the light / dark / system mode of the AgentOS design
 * tokens: a `select` for the skin and an icon button that cycles the mode.
 *
 * It drives DsThemeService, which shares its choice with Coday and the AgentOS UI through localStorage.
 *
 * CSS contract: --color-divider, --color-surface, --color-text, --color-accent, --color-bg-hover, --font-body,
 * --radius-md, --radius-pill
 *
 * @example
 * <ds-theme-picker />
 */
@Component({
  selector: 'ds-theme-picker',
  imports: [MatIcon],
  templateUrl: './theme-picker.component.html',
  styleUrl: './theme-picker.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ThemePickerComponent {
  protected readonly themes = inject(DsThemeService)
  protected readonly theme = this.themes.theme
  protected readonly variants = DS_THEME_VARIANTS

  protected readonly modeIcon = computed(() => MODE_ICONS[this.theme().mode])
  protected readonly modeTitle = computed(() => {
    const mode = this.theme().mode
    return `Theme: ${mode} (click for ${nextThemeMode(mode)})`
  })

  protected onVariantChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value
    if (isThemeVariant(value)) this.themes.setVariant(value)
  }
}
