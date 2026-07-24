import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core'
import { Case } from '@whoz-oss/agentos-api-client'
import { CaseStatusGlyphComponent } from '../../case-status-glyph/case-status-glyph.component'

interface CaseGroup {
  label: string
  cases: (Case & { starred?: boolean })[]
}

/**
 * ShellCaseSwitcherMobileComponent — mobile case drawer.
 *
 * Remplace l'ancien sub-header case-switcher par un drawer overlay
 * slide-in depuis la gauche (maquette mobile).
 *
 * Structure:
 *   - Backdrop semi-transparent (ferme au clic)
 *   - Panneau 318px: header "Cases" + close | search | liste groupée | user menu
 */
@Component({
  selector: 'agentos-shell-case-switcher-mobile',
  imports: [CaseStatusGlyphComponent],
  templateUrl: './shell-case-switcher-mobile.component.html',
  styleUrl: './shell-case-switcher-mobile.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ShellCaseSwitcherMobileComponent {
  // ── Inputs ────────────────────────────────────────────
  readonly open = input.required<boolean>()
  readonly cases = input.required<(Case & { starred?: boolean })[]>()
  readonly activeCaseId = input.required<string | null>()
  readonly userInitials = input.required<string>()
  readonly userName = input.required<string>()
  readonly isAdmin = input.required<boolean>()
  readonly isDark = input.required<boolean>()

  // ── Internal state ────────────────────────────────────────
  protected readonly filterQuery = signal('')
  protected readonly confirmingDeleteId = signal<string | null>(null)
  protected readonly userMenuOpen = signal(false)

  // ── Derived: grouped + filtered cases ───────────────────────
  protected readonly groupedCases = computed<CaseGroup[]>(() => {
    const q = this.filterQuery().toLowerCase()
    const all = this.cases().filter((c) => !q || (c.title ?? c.id ?? '').toLowerCase().includes(q))

    const pinned = all.filter((c) => c.starred)
    const today = all.filter((c) => !c.starred && this.isToday(c))
    const yest = all.filter((c) => !c.starred && this.isYesterday(c))
    const prev7 = all.filter((c) => !c.starred && this.isPrev7(c))
    const older = all.filter((c) => !c.starred && !this.isToday(c) && !this.isYesterday(c) && !this.isPrev7(c))

    const groups: CaseGroup[] = []
    if (pinned.length) groups.push({ label: 'Pinned', cases: pinned })
    if (today.length) groups.push({ label: 'Today', cases: today })
    if (yest.length) groups.push({ label: 'Yesterday', cases: yest })
    if (prev7.length) groups.push({ label: 'Previous 7 days', cases: prev7 })
    if (older.length) groups.push({ label: 'Older', cases: older })
    return groups
  })

  // ── Outputs ────────────────────────────────────────────
  readonly closed = output<void>()
  readonly caseSelected = output<string>()
  readonly starToggled = output<{ id: string; starred: boolean }>()
  readonly deleteRequested = output<string>()
  readonly navigateTo = output<string>()
  readonly themeToggled = output<void>()
  readonly logsToggled = output<void>()

  // ── Handlers ────────────────────────────────────────────
  protected onCaseSelect(id: string): void {
    this.confirmingDeleteId.set(null)
    this.caseSelected.emit(id)
  }

  protected onStarToggle(event: Event, c: Case & { starred?: boolean }): void {
    event.stopPropagation()
    this.starToggled.emit({ id: c.id ?? '', starred: !c.starred })
  }

  protected onDeleteRequest(event: Event, id: string): void {
    event.stopPropagation()
    this.confirmingDeleteId.set(id)
  }

  protected onDeleteConfirm(event: Event, id: string | undefined): void {
    event.stopPropagation()
    this.confirmingDeleteId.set(null)
    if (id) this.deleteRequested.emit(id)
  }

  protected onDeleteCancel(event: Event): void {
    event.stopPropagation()
    this.confirmingDeleteId.set(null)
  }

  protected onMenuNavigate(path: string): void {
    this.userMenuOpen.set(false)
    this.closed.emit()
    this.navigateTo.emit(path)
  }

  protected onMenuThemeToggle(): void {
    this.userMenuOpen.set(false)
    this.themeToggled.emit()
  }

  protected onMenuLogsToggle(): void {
    this.userMenuOpen.set(false)
    this.logsToggled.emit()
  }

  // ── Date helpers ──────────────────────────────────────────
  private dayStart(d: Date): number {
    return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  }

  private getModified(c: Case): number {
    const raw =
      (c as unknown as Record<string, unknown>)['modified'] ?? (c as unknown as Record<string, unknown>)['created']
    return raw ? new Date(raw as string).getTime() : 0
  }

  private isToday(c: Case): boolean {
    const now = Date.now()
    const start = this.dayStart(new Date())
    const m = this.getModified(c)
    return m >= start && m <= now
  }

  private isYesterday(c: Case): boolean {
    const start = this.dayStart(new Date()) - 86400000
    const end = this.dayStart(new Date())
    const m = this.getModified(c)
    return m >= start && m < end
  }

  private isPrev7(c: Case): boolean {
    const start = this.dayStart(new Date()) - 7 * 86400000
    const end = this.dayStart(new Date()) - 86400000
    const m = this.getModified(c)
    return m >= start && m < end
  }
}
