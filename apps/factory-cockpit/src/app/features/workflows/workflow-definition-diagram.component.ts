import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core'
import { FullWorkflowStep } from '../../core/factory-api.service'

/** Deterministic DAG layout; definition order breaks ties, never changes dependency data. */
export function layoutDefinition(steps: FullWorkflowStep[]): {
  nodes: { step: FullWorkflowStep; x: number; y: number }[]
  edges: { from: string; to: string; path: string; label: string; x: number; y: number }[]
  width: number
  height: number
} {
  const levels = new Map<string, number>()
  const index = new Map(steps.map((step) => [step.id, step]))
  function level(id: string, visiting = new Set<string>()): number {
    if (levels.has(id)) return levels.get(id) ?? 0
    if (visiting.has(id)) return 0 // Defensive rendering only; backend rejects cyclic definitions.
    visiting.add(id)
    const dependencies = (index.get(id)?.dependsOn ?? []).filter((dep) => index.has(dep))
    const value = dependencies.length ? 1 + Math.max(...dependencies.map((dep) => level(dep, new Set(visiting)))) : 0
    levels.set(id, value)
    return value
  }
  const rows = new Map<number, number>()
  const nodes = steps.map((step) => {
    const column = level(step.id)
    const row = rows.get(column) ?? 0
    rows.set(column, row + 1)
    return { step, x: column * 360 + 16, y: row * 180 + 16 }
  })
  const positioned = new Map(nodes.map((node) => [node.step.id, node]))
  const edges = nodes.flatMap((node) =>
    (node.step.dependsOn ?? []).flatMap((id) => {
      const parent = positioned.get(id)
      if (!parent) return []
      const x1 = parent.x + 216
      const y1 = parent.y + 40
      const x2 = node.x
      const y2 = node.y + 40
      const route = parent.step.decision?.routes.find((item) => item.target === node.step.id)
      const label = route ? `${route.name} (${parent.step.decision?.mode})` : node.step.join ? 'join' : ''
      return [
        {
          label,
          x: (x1 + x2) / 2,
          y: (y1 + y2) / 2 - 8,
          from: id,
          to: node.step.id,
          path: `M ${x1} ${y1} C ${x1 + 22} ${y1}, ${x2 - 22} ${y2}, ${x2} ${y2}`,
        },
      ]
    })
  )
  return {
    nodes,
    edges,
    width: Math.max(260, ...nodes.map((node) => node.x + 240)),
    height: Math.max(180, ...nodes.map((node) => node.y + 164)),
  }
}

@Component({
  selector: 'sf-workflow-definition-diagram',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="scroll" role="region" aria-label="Workflow steps and dependencies" tabindex="0">
      <div class="diagram" [style.width.px]="layout().width" [style.height.px]="layout().height">
        <svg [attr.width]="layout().width" [attr.height]="layout().height" aria-hidden="true">
          <defs>
            <marker
              id="definition-arrow"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="6"
              markerHeight="6"
              orient="auto-start-reverse"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" />
            </marker>
          </defs>
          @for (edge of layout().edges; track edge.from + ':' + edge.to) {
            <path
              class="edge"
              [attr.d]="edge.path"
              marker-end="url(#definition-arrow)"
              [attr.data-dependency]="edge.from + ':' + edge.to"
            />
            @if (edge.label) {
              <text [attr.x]="edge.x" [attr.y]="edge.y" text-anchor="middle" font-size="10">{{ edge.label }}</text>
            }
          }
        </svg>
        @for (node of layout().nodes; track node.step.id) {
          <button
            type="button"
            [style.left.px]="node.x"
            [style.top.px]="node.y"
            [attr.data-step-node]="node.step.id"
            [attr.aria-pressed]="selectedId() === node.step.id"
            [disabled]="disabled()"
            (click)="selected.emit(node.step.id)"
          >
            <strong>{{ node.step.name }}</strong>
            <span>{{ node.step.responsibility?.kind }} · {{ node.step.responsibility?.name }}</span>
            @if (node.step.decision) {
              <span>Decision · {{ node.step.decision.mode }}</span>
            }
            @if (node.step.join) {
              <span>Join · {{ node.step.join }}</span>
            }
            @if (node.step.status) {
              <span>{{ node.step.status }} · {{ node.step.activation }}</span>
            }
            @if (node.step.routeDecision) {
              <span>Selected: {{ node.step.routeDecision.selectedRoutes.join(', ') }}</span>
            }
            <span class="sr-only">Depends on: {{ node.step.dependsOn?.join(', ') || 'none' }}</span>
            @for (route of node.step.decision?.routes ?? []; track route.name) {
              <span class="sr-only">Route {{ route.name }} to {{ route.target }}</span>
            }
          </button>
        }
      </div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      min-width: 0;
    }
    .scroll {
      min-width: 0;
      max-width: 100%;
      box-sizing: border-box;
      overflow: auto;
      border: 1px solid var(--sf-outline);
      border-radius: 8px;
    }
    .diagram {
      position: relative;
    }
    svg {
      position: absolute;
      inset: 0;
      pointer-events: none;
      fill: var(--sf-text-secondary, currentColor);
    }
    .edge {
      fill: none;
      stroke: var(--sf-text-secondary, currentColor);
      stroke-width: 2;
    }
    button {
      position: absolute;
      width: 216px;
      min-height: 80px;
      padding: 12px;
      border: 1px solid var(--sf-outline);
      border-radius: 8px;
      background: var(--sf-surface-2);
      color: inherit;
      cursor: pointer;
      text-align: left;
    }
    button[aria-pressed='true'] {
      outline: 2px solid var(--sf-primary, #9fa8ff);
    }
    button:focus-visible {
      outline: 3px solid var(--sf-primary, #9fa8ff);
      outline-offset: 3px;
    }
    strong,
    span {
      display: block;
      overflow-wrap: anywhere;
    }
    strong {
      overflow: hidden;
      display: -webkit-box;
      -webkit-line-clamp: 2;
      -webkit-box-orient: vertical;
    }
    span {
      font-size: 12px;
      margin-top: 4px;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
    }
  `,
})
export class WorkflowDefinitionDiagramComponent {
  readonly steps = input<FullWorkflowStep[]>([])
  readonly selectedId = input<string | null>(null)
  readonly disabled = input(false)
  readonly selected = output<string>()
  readonly layout = computed(() => layoutDefinition(this.steps()))
}
