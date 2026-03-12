import { DecimalPipe } from '@angular/common'
import { FormsModule } from '@angular/forms'
import { Component, computed, inject, OnInit, signal } from '@angular/core'
import { Router } from '@angular/router'
import { TokenUsageStateService } from '../../core/services/token-usage-state.service'
import { ProjectStateService } from '../../core/services/project-state.service'

@Component({
  selector: 'app-token-usage',
  standalone: true,
  imports: [FormsModule, DecimalPipe],
  templateUrl: './token-usage.component.html',
  styleUrl: './token-usage.component.scss',
})
export class TokenUsageComponent implements OnInit {
  private readonly router = inject(Router)
  private readonly projectState = inject(ProjectStateService)
  protected readonly state = inject(TokenUsageStateService)

  protected readonly view = signal<'byAgent' | 'byModel'>('byModel')

  protected readonly rowsByAgent = computed(() => this.state.aggregation()?.models ?? [])
  protected readonly totalRow = computed(() => this.state.aggregation()?.total ?? null)

  protected readonly rowsByModel = computed(() => this.state.aggregationByModelId() ?? [])

  protected readonly rows = computed(() => {
    return this.view() === 'byAgent' ? this.rowsByAgent() : this.rowsByModel()
  })

  protected readonly isEmpty = computed(() => {
    const agg = this.state.aggregation()
    if (!agg) return false
    return (agg.models?.length ?? 0) === 0 && (agg.total?.callCount ?? 0) === 0
  })

  protected readonly totalCost = computed(() => this.totalRow()?.cost ?? 0)

  ngOnInit(): void {
    this.state.init()
  }

  protected onFromChange(value: string): void {
    this.state.setDraftFrom(value || null)
  }

  protected onToChange(value: string): void {
    this.state.setDraftTo(value || null)
  }

  protected clearFrom(): void {
    this.state.clearDraftFrom()
  }

  protected clearTo(): void {
    this.state.clearDraftTo()
  }

  protected apply(): void {
    this.state.apply()
  }

  protected trackRow(
    _index: number,
    row: import('../../core/services/token-usage-api.service').ModelUsageSummaryDto,
  ): string {
    return `${row.modelName}|${row.providerName}|${row.modelId}`
  }

  protected navigateBackToProject(): void {
    const projectName = this.projectState.getSelectedProjectId()

    if (projectName) {
      this.router.navigate(['project', projectName])
      return
    }

    this.router.navigate(['/'])
  }
}
