import { ChangeDetectionStrategy, Component, DestroyRef, OnInit, inject, signal } from '@angular/core'
import { takeUntilDestroyed } from '@angular/core/rxjs-interop'
import { ActivatedRoute, Router, RouterLink } from '@angular/router'
import { AgentConfigControllerService, AgentDefinition, AgentDefinitionToolSummary } from '@whoz-oss/agentos-api-client'
import { MarkdownPipe } from '../../pipes/markdown.pipe'

/**
 * AgentConfigInspectComponent — read-only view of a resolved AgentDefinition.
 *
 * Calls `getDefinitionAgentConfig(agentConfigId)` and displays the result
 * in structured sections:
 * - General info (resolved model, provider, flags)
 * - Instructions (collapsible if present)
 * - Tools list (each tool expandable to show its JSON input schema)
 *
 * Route: `/:namespaceId/agent-configs/:agentConfigId/inspect`
 * Navigates back to `/:namespaceId/agent-configs` on cancel.
 */
@Component({
  selector: 'agentos-agent-config-inspect',
  standalone: true,
  imports: [RouterLink, MarkdownPipe],
  templateUrl: './agent-config-inspect.component.html',
  styleUrl: './agent-config-inspect.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AgentConfigInspectComponent implements OnInit {
  private readonly route = inject(ActivatedRoute)
  private readonly router = inject(Router)
  private readonly destroyRef = inject(DestroyRef)
  private readonly agentConfigController = inject(AgentConfigControllerService)

  protected readonly namespaceId: string | undefined = this.route.snapshot.params['namespaceId'] as string | undefined
  protected readonly agentConfigId = this.route.snapshot.params['agentConfigId'] as string

  /** True when viewing a platform-level config (no namespaceId in route). */
  protected readonly isPlatformMode = !this.namespaceId

  protected readonly isLoading = signal(true)
  protected readonly hasError = signal(false)
  protected readonly definition = signal<AgentDefinition | null>(null)

  /** Tracks which tool rows are expanded (by tool name). */
  protected readonly expandedTools = signal<Set<string>>(new Set())

  /** Whether the system prompt section is collapsed. */
  protected readonly systemPromptCollapsed = signal(true)

  /** Whether the instructions section is collapsed. */
  protected readonly instructionsCollapsed = signal(true)

  ngOnInit(): void {
    this.agentConfigController
      .getDefinitionAgentConfig(this.agentConfigId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (def) => {
          this.definition.set(def)
          this.isLoading.set(false)
        },
        error: () => {
          this.hasError.set(true)
          this.isLoading.set(false)
        },
      })
  }

  protected toggleTool(tool: AgentDefinitionToolSummary): void {
    this.expandedTools.update((current) => {
      const next = new Set(current)
      if (next.has(tool.name)) {
        next.delete(tool.name)
      } else {
        next.add(tool.name)
      }
      return next
    })
  }

  protected isToolExpanded(tool: AgentDefinitionToolSummary): boolean {
    return this.expandedTools().has(tool.name)
  }

  protected toggleSystemPrompt(): void {
    this.systemPromptCollapsed.update((v) => !v)
  }

  protected toggleInstructions(): void {
    this.instructionsCollapsed.update((v) => !v)
  }

  /**
   * Parse and pretty-print the JSON input schema string.
   * Falls back to the raw string if parsing fails.
   */
  protected formatSchema(rawSchema: string): string {
    try {
      return JSON.stringify(JSON.parse(rawSchema), null, 2)
    } catch {
      return rawSchema
    }
  }

  /** Route to the edit form for this agent config. */
  protected editRoute(): string[] {
    if (this.isPlatformMode) {
      return ['/agentos', 'admin', 'agent-configs', this.agentConfigId, 'edit']
    }
    return ['/agentos', this.namespaceId!, 'agent-configs', this.agentConfigId, 'edit']
  }

  protected back(): void {
    if (this.isPlatformMode) {
      this.router.navigate(['/agentos', 'admin', 'agent-configs'])
    } else {
      this.router.navigate(['/agentos', this.namespaceId, 'agent-configs'])
    }
  }
}
