import { ChangeDetectionStrategy, Component, input } from '@angular/core'
import { CaseStatusEventStatusEnum } from '@whoz-oss/agentos-api-client'

/**
 * CaseStatusGlyphComponent — SVG glyph représentant le statut d'un case.
 *
 * Usage:
 *   <agentos-case-status-glyph [status]="caseStatus()" [size]="16" />
 *
 * Couleur : via CSS `color` sur le host (→ currentColor dans le SVG).
 * Animation : via la classe CSS `--running` ou `--pending` injectée sur le host.
 *
 * Glyphs (alignés sur la maquette) :
 *   IDLE    — cercle stroke simple
 *   RUNNING — cercle fond 25% + arc animé (rotation)
 *   PENDING — cercle stroke 50% + point central pulsant
 *   KILLED  — cercle plein + barre horizontale blanche
 *   ERROR   — cercle plein + trait vertical blanc + point blanc
 */
@Component({
  selector: 'agentos-case-status-glyph',
  templateUrl: './case-status-glyph.component.html',
  styleUrl: './case-status-glyph.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '[class.is-running]': 'status() === Status.RUNNING',
    '[class.is-pending]': 'status() === Status.PENDING',
    '[class.is-error]': 'status() === Status.ERROR',
    '[class.is-killed]': 'status() === Status.KILLED',
  },
})
export class CaseStatusGlyphComponent {
  /** Statut du case — pilote le glyph et la couleur via les classes host. */
  readonly status = input.required<string>()

  /** Taille du SVG en px (défaut : 16). */
  readonly size = input<number>(16)

  protected readonly Status = CaseStatusEventStatusEnum
}
